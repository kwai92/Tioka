package com.tioka.server.gate;

import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.biz.LeaseBizStubServer;
import com.tioka.server.gate.support.embedding.ChineseEmbeddingContainer;
import com.tioka.server.gate.support.erp.FakeErpMultiStepScenarios;
import com.tioka.server.gate.support.mail.MailpitContainer;
import com.tioka.server.gate.support.mail.SmtpCaptureStub;
import com.tioka.server.gate.support.metrics.PrometheusScrapeSupport;
import com.tioka.server.gate.support.ocr.OcrStubServer;
import com.tioka.server.support.TestContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENG-04d：G3 闸门基座冒烟 —
 * 双实例 health + Broker + Keycloak 区域用户 + 业务/OCR/SMTP(Mailpit) +
 * FakeErp 多步 + 中文 embedding + Prometheus 抓取。
 */
@Tag("gate")
@Tag("mq")
class G3GateInfraSmokeIT {

    @Test
    void dualHealthBrokerKeycloakAndAllG3Stubs() throws Exception {
        TestContainers.ROCKETMQ.ensureStarted();

        try (LeaseBizStubServer biz = LeaseBizStubServer.start();
             OcrStubServer ocr = OcrStubServer.start();
             SmtpCaptureStub smtpMem = SmtpCaptureStub.start();
             MailpitContainer mailpit = MailpitContainer.start();
             ChineseEmbeddingContainer embedding = ChineseEmbeddingContainer.start();
             GateCluster cluster = GateCluster.start(2, "three-tools", Map.of(
                     "tioka.messaging.mode", "rocketmq",
                     "tioka.messaging.name-server", TestContainers.ROCKETMQ.nameServerAddr(),
                     "rocketmq.name-server", TestContainers.ROCKETMQ.nameServerAddr()
             ), true)) {
            assertEquals(2, cluster.size());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, cluster.baseUrlA() + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrlB() + "/actuator/health").statusCode());

            // Broker 可达
            assertFalse(TestContainers.ROCKETMQ.nameServerAddr().isBlank());

            // Keycloak + 华东/华南区域用户
            KeycloakGateSupport kc = cluster.keycloak();
            kc.ensureRegionUsers();
            assertEquals(KeycloakGateSupport.REGION_EAST, kc.userRegionAttribute(KeycloakGateSupport.USER_EAST));
            assertEquals(KeycloakGateSupport.REGION_SOUTH, kc.userRegionAttribute(KeycloakGateSupport.USER_SOUTH));
            assertNotEquals(kc.userRegionAttribute(KeycloakGateSupport.USER_EAST),
                    kc.userRegionAttribute(KeycloakGateSupport.USER_SOUTH));

            // 业务桩
            assertFalse(biz.baseUrl().isBlank());
            assertEquals(200, get(client, biz.baseUrl() + "/api/contracts/C-DEMO").statusCode());
            biz.emitSubmitted("C-DEMO", "S001");
            assertFalse(biz.events().isEmpty());

            // OCR：命中录制 + 未知显式失败
            HttpResponse<String> ocrHit = client.send(
                    HttpRequest.newBuilder(URI.create(ocr.baseUrl() + "/ocr/recognize"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\"sha256:sample-scan-1\"}"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ocrHit.statusCode());
            assertTrue(ocrHit.body().contains("sample-scan-1") || ocrHit.body().contains("RECORDED"),
                    ocrHit.body());

            HttpResponse<String> ocrMiss = client.send(
                    HttpRequest.newBuilder(URI.create(ocr.baseUrl() + "/ocr/recognize"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\"nope\"}"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, ocrMiss.statusCode());
            assertTrue(ocrMiss.body().contains("OCR_FIXTURE_MISS"));

            // SMTP：内存桩 + Mailpit 容器
            smtpMem.accept("gate@example.com", "smoke", "ok");
            assertEquals("smoke", smtpMem.waitForMessage("gate@", Duration.ofSeconds(2)).subject());
            mailpit.inject("lawyer@example.com", "mailpit-smoke", "body");
            var captured = mailpit.waitForMessage("lawyer@", Duration.ofSeconds(5));
            assertTrue(captured.path("Subject").asText().contains("mailpit-smoke")
                            || captured.toString().contains("lawyer"),
                    () -> "mailpit should capture injected mail: " + captured);

            // FakeErp 多步：5 步复原 + 补偿失败 + before 漂移
            FakeErpMultiStepScenarios.FiveStepResult five = FakeErpMultiStepScenarios.runFiveWriteoffs();
            FakeErpMultiStepScenarios.compensateAllReverse(five);
            assertEquals("ar_s3", FakeErpMultiStepScenarios.compensateFailureStopsAtThird());
            FakeErpMultiStepScenarios.beforeMutatedRejectsApply();

            // 中文 embedding + rerank
            assertEquals(ChineseEmbeddingContainer.DEFAULT_DIM, embedding.embedSmoke("华东租赁合同条款"));
            int top = embedding.rerankTopIndex("华南门店租金", "无关文档", "华南门店租金上涨条款", "华北天气");
            assertEquals(1, top);

            // Prometheus / metrics 双实例抓取
            Map<String, PrometheusScrapeSupport.Scrape> scrapes =
                    new PrometheusScrapeSupport().scrapeBoth(cluster.baseUrlA(), cluster.baseUrlB());
            assertEquals(200, scrapes.get("A").status());
            assertEquals(200, scrapes.get("B").status());
            assertFalse(scrapes.get("A").body().isBlank());
            assertFalse(scrapes.get("B").body().isBlank());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

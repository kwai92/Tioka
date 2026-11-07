package com.tioka.server.gate;

import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.biz.LeaseBizStubServer;
import com.tioka.server.gate.support.mail.SmtpCaptureStub;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENG-04d：G3 闸门基座冒烟 — 双实例 health + 业务/OCR/SMTP 桩。
 */
@Tag("gate")
class G3GateInfraSmokeIT {

    @Test
    void dualHealthAndBizOcrSmtpStubs() throws Exception {
        TestContainers.ROCKETMQ.ensureStarted();

        try (LeaseBizStubServer biz = LeaseBizStubServer.start();
             OcrStubServer ocr = OcrStubServer.start();
             SmtpCaptureStub smtp = SmtpCaptureStub.start();
             GateCluster cluster = GateCluster.start(2, "three-tools", Map.of(
                     "tioka.messaging.mode", "rocketmq",
                     "tioka.messaging.name-server", TestContainers.ROCKETMQ.nameServerAddr(),
                     "rocketmq.name-server", TestContainers.ROCKETMQ.nameServerAddr()
             ), true)) {
            assertEquals(2, cluster.size());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, cluster.baseUrlA() + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrlB() + "/actuator/health").statusCode());

            assertFalse(biz.baseUrl().isBlank());
            assertEquals(200, get(client, biz.baseUrl() + "/api/contracts/C-DEMO").statusCode());

            HttpResponse<String> ocrMiss = client.send(
                    HttpRequest.newBuilder(URI.create(ocr.baseUrl() + "/ocr/recognize"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\"nope\"}"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, ocrMiss.statusCode());
            assertTrue(ocrMiss.body().contains("OCR_FIXTURE_MISS"));

            smtp.accept("gate@example.com", "smoke", "ok");
            assertEquals("smoke", smtp.waitForMessage("gate@", Duration.ofSeconds(2)).subject());

            biz.emitSubmitted("C-DEMO", "S001");
            assertFalse(biz.events().isEmpty());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

package com.tioka.server.gate;

import com.tioka.connector.infrastructure.FeishuImChannelAdapter;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.feishu.FeishuStubServer;
import com.tioka.server.support.TestContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ENG-04c：G2 闸门基座冒烟 — 双实例 health + RocketMQ 可达 + 飞书桩
 * （url_verification / 签名加密推送 / 出站录制 / 频控 / 令牌刷新）+ 按名停启。
 * 不含业务 G2 骨架转绿。
 */
@Tag("gate")
@Tag("mq")
class G2GateInfraSmokeIT {

    @Test
    void dualHealthMqReachableAndFeishuStubOnceEach() throws Exception {
        TestContainers.ROCKETMQ.ensureStarted();
        assertTrue(reachable(TestContainers.ROCKETMQ.nameServerHost(),
                        TestContainers.ROCKETMQ.nameServerPort(), Duration.ofSeconds(5)),
                "NameServer 应可达: " + TestContainers.ROCKETMQ.nameServerAddr());

        FeishuStubServer feishu = FeishuStubServer.start();
        try (GateCluster cluster = GateCluster.start(2, "three-tools", Map.of(
                "tioka.messaging.mode", "rocketmq",
                "tioka.messaging.name-server", TestContainers.ROCKETMQ.nameServerAddr(),
                "rocketmq.name-server", TestContainers.ROCKETMQ.nameServerAddr(),
                "tioka.feishu.api-base", feishu.baseUrl(),
                "tioka.feishu.app-id", "cli_gate_smoke",
                "tioka.feishu.app-secret", "sec_gate_smoke"
        ), true)) {
            assertEquals(2, cluster.size());
            assertNotEquals(cluster.portA(), cluster.portB());
            assertNotNull(cluster.keycloak(), "基座已有 Keycloak 应保留");

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, cluster.baseUrlA() + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrlB() + "/actuator/health").statusCode());

            // 飞书桩：url_verification
            feishu.clear();
            String challenge = feishu.pushUrlVerification(feishu.urlVerificationEchoUrl(), "chal-g2-smoke");
            assertEquals("chal-g2-smoke", challenge);
            assertEquals(1, feishu.pushRecords().size());
            assertTrue(feishu.pushRecords().get(0).body().contains("url_verification"));

            // 飞书桩：签名 + 加密推送（回声端点验签解密）
            String plain = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                    + "\"event\":{\"message\":{\"message_id\":\"om_smoke\"}}}";
            HttpResponse<String> encryptedEcho = feishu.pushSignedEncryptedEvent(
                    feishu.eventEchoUrl(), plain);
            assertEquals(200, encryptedEcho.statusCode());
            assertTrue(encryptedEcho.body().contains("im.message.receive_v1"), encryptedEcho.body());

            // 飞书桩：卡片按钮回调推送录制
            feishu.clear();
            feishu.pushCardAction(feishu.eventEchoUrl(), Map.of(
                    "action", "approve",
                    "approvalId", "apr_smoke"));
            assertEquals(1, feishu.pushRecords().size());
            assertTrue(feishu.pushRecords().get(0).body().contains("card.action.trigger")
                            || feishu.pushRecords().get(0).responseBody().contains("card.action.trigger"),
                    String.valueOf(feishu.pushRecords()));

            // 飞书桩：出站录制（经现有 FeishuImChannelAdapter → OpenAPI）
            FeishuImChannelAdapter adapter = cluster.a().getBean(FeishuImChannelAdapter.class);
            adapter.sendCard("oc_gate_smoke", Map.of(
                    "config", Map.of("wide_screen_mode", true),
                    "elements", java.util.List.of(Map.of(
                            "tag", "div",
                            "text", Map.of("tag", "plain_text", "content", "g2-infra-smoke")))));
            assertFalse(feishu.outboundCalls().isEmpty(), "应录到至少一次 token 或发消息");
            assertFalse(feishu.outboundMessages().isEmpty(), "应录到一次 im messages create");
            assertTrue(feishu.outboundMessages().stream().anyMatch(r ->
                            r.body() != null && (r.body().contains("g2-infra-smoke")
                                    || r.body().contains("oc_gate_smoke"))),
                    String.valueOf(feishu.outboundMessages()));

            // 飞书桩：频控错误码（下一次 im messages create）
            String probeToken = feishu.issueTenantAccessToken();
            feishu.failNextMessageWithRateLimit();
            HttpResponse<String> rateLimited = feishu.stubPostMessage(probeToken, "oc_rate", "rate-limit-probe");
            assertTrue(rateLimited.body().contains("99991400")
                            || rateLimited.body().contains("230020"),
                    "应返回飞书频控码: " + rateLimited.body());
            assertTrue(feishu.rateLimitResponses() >= 1);

            // 飞书桩：令牌刷新（旧 token 失效 → 再颁发 → 新 token 可用）
            String oldToken = feishu.issueTenantAccessToken();
            int tokensBefore = feishu.tokenIssueCount();
            feishu.invalidateTenantToken();
            HttpResponse<String> rejected = feishu.stubPostMessage(oldToken, "oc_old", "old-token");
            assertTrue(rejected.body().contains("99991663")
                            || rejected.body().contains("token"),
                    "旧 token 应被拒: " + rejected.body());
            String newToken = feishu.issueTenantAccessToken();
            assertTrue(feishu.tokenIssueCount() > tokensBefore);
            HttpResponse<String> accepted = feishu.stubPostMessage(newToken, "oc_new", "new-token");
            assertTrue(accepted.body().contains("\"code\":0") || accepted.body().contains("\"code\": 0"),
                    accepted.body());

            // 按名称停止 / 启动 B（与 crash 分离）
            int portBBefore = cluster.portB();
            cluster.stop("B");
            assertThrows(Exception.class, () -> get(client, "http://127.0.0.1:" + portBBefore + "/actuator/health"));
            cluster.startInstance("B");
            assertEquals(200, get(client, cluster.baseUrlA() + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrlB() + "/actuator/health").statusCode());
            assertEquals("B", cluster.instance(1).getEnvironment()
                    .getProperty("tioka.runtime.instance-id"));
            assertTrue(portBBefore > 0);
        } finally {
            feishu.close();
        }
    }

    private static boolean reachable(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

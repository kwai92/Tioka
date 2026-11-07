package com.tioka.server.gate;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import com.tioka.server.gate.support.McpStubServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ENG-04b：闸门基座冒烟 — Keycloak + 3 实例 + LLM/MCP 桩 + crash 租约过期。
 */
@Tag("gate")
class GateInfraSmokeIT {

    @Test
    void keycloakLoginMcpCallAndCrashLeaseExpiry() throws Exception {
        try (GateCluster cluster = GateCluster.start(3)) {
            assertEquals(3, cluster.size());
            assertNotEquals(cluster.port(0), cluster.port(1));
            assertNotEquals(cluster.port(1), cluster.port(2));

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, cluster.baseUrl(0) + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrl(1) + "/actuator/health").statusCode());
            assertEquals(200, get(client, cluster.baseUrl(2) + "/actuator/health").statusCode());

            KeycloakLogin login = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0));
            String token = login.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
            assertTrue(token.startsWith("tka_"), token);

            McpStubServer mcp = cluster.mcp();
            mcp.clearCalls();
            String rpc = """
                    {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"erp.query","arguments":{"q":"smoke"}}}
                    """;
            HttpResponse<String> mcpResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcp.baseUrl() + "/"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(rpc))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mcpResp.statusCode());
            assertTrue(mcpResp.body().contains("query-ok"), mcpResp.body());
            assertEquals(1, mcp.calls().size());
            assertEquals("erp.query", mcp.calls().get(0).name());

            String turnId = plantRunningLease(cluster.instance(0), "A");
            Instant plantedAt = Instant.now();
            cluster.crash(0);

            ConfigurableApplicationContext b = cluster.instance(1);
            TurnRepository turns = b.getBean(TurnRepository.class);
            JdbcTemplate jdbcB = b.getBean(JdbcTemplate.class);
            Instant detectedAt = null;
            String detectHow = null;
            // 闸门 reaper 1s 扫描：过期窗口极短，须同时接受「可见过期」与「已被兄弟实例回收」
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                List<String> found = turns.findExpired(Instant.now(), 50);
                if (found.contains(turnId)) {
                    detectedAt = Instant.now();
                    detectHow = "findExpired";
                    break;
                }
                // 用 PG now() 判定，避免 JVM/DB 时区漂移导致租约「永不过期」
                Boolean dbExpired = jdbcB.queryForObject(
                        "SELECT lease_until IS NOT NULL AND lease_until < now() FROM runtime_turn WHERE id = ?",
                        Boolean.class, turnId);
                if (Boolean.TRUE.equals(dbExpired)) {
                    detectedAt = Instant.now();
                    detectHow = "dbExpired";
                    break;
                }
                // LeaseReaper 已在另一实例把 RUNNING 收走（lease_until 清空）也算跨实例可见过期
                String status = jdbcB.queryForObject(
                        "SELECT status FROM runtime_turn WHERE id = ?", String.class, turnId);
                if (status != null && !"RUNNING".equals(status)) {
                    detectedAt = Instant.now();
                    detectHow = "reaped:" + status;
                    break;
                }
                Thread.sleep(100);
            }
            assertNotNull(detectedAt,
                    "另一实例应在 15 秒内看到租约过期或完成回收；plantedAt=" + plantedAt);
            assertTrue(Duration.between(plantedAt, detectedAt).toMillis() <= 15_000,
                    "检测到过期应在 plant 后 15s 内；how=" + detectHow
                            + " plantedAt=" + plantedAt + " detectedAt=" + detectedAt);
        }
    }

    private static String plantRunningLease(ConfigurableApplicationContext ctx, String workerId) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        TurnRepository turns = ctx.getBean(TurnRepository.class);
        RuntimeSessionRepository sessions = ctx.getBean(RuntimeSessionRepository.class);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");
        principals.runAs(new Principal("10", "ws_gate", PrincipalType.USER,
                Set.of("member"), Set.of("*"), "test"), () -> {
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_gate");
            s.setWorkspaceId("ws_gate");
            s.setOwnerId("10");
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setSessionId(threadId);
            t.setSeq(1);
            t.setStatus(TurnStatus.RUNNING.name());
            t.setOrigin("chat");
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"ws_gate\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(1L);
            t.setWorkerId(workerId);
            turns.insert(t);
        });

        // 用 PG now() 设短 TTL，与判定侧同一时钟；reaper 1s 扫描下约 2s 即可过期/回收
        jdbc.update("UPDATE runtime_turn SET worker_id = ?, lease_until = now() + interval '2 seconds' WHERE id = ?",
                workerId, turnId);
        return turnId;
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

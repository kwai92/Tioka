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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
            boolean expired = false;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                List<String> found = turns.findExpired(Instant.now(), 50);
                if (found.contains(turnId)) {
                    expired = true;
                    break;
                }
                RuntimeTurnDO row = turns.findById(turnId).orElseThrow();
                if (row.getLeaseUntil() != null
                        && row.getLeaseUntil().toInstant().isBefore(Instant.now())) {
                    expired = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(expired, "另一实例应在 10 秒内看到租约过期；plantedAt=" + plantedAt);
            assertTrue(Duration.between(plantedAt, Instant.now()).toMillis() <= 10_000);
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

        OffsetDateTime until = OffsetDateTime.ofInstant(Instant.now().plusSeconds(5), ZoneOffset.UTC);
        jdbc.update("UPDATE runtime_turn SET worker_id = ?, lease_until = ? WHERE id = ?",
                workerId, until, turnId);
        return turnId;
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

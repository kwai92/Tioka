package com.tioka.server.flow;

import com.tioka.flow.application.FlowService;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.QuotaService;
import com.tioka.system.application.quota.DefaultQuotaGuard;
import com.tioka.system.application.quota.QuotaMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FLW-02：Flow LLM 节点经 ModelGateway，用量计入配额。见 M1-03 FLW-02。
 */
@SpringBootTest
@Tag("it")
class FlowLlmNodeIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired FlowService flows;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired UsageRecorder usageRecorder;
    @Autowired QuotaService quotas;
    @Autowired DefaultQuotaGuard quotaGuard;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void usageCountedInQuota() {
        String ws = Ids.newId("ws");
        String userId = "1";
        principals.runAs(user(userId, ws), () -> {
            clearTok(ws, userId);
            quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, ws, QuotaMetrics.TOKENS_MONTH, 1_000_000L);

            String planId = seedChatPlan();
            var draft = flows.saveDraft("flw02", Map.of(
                    "nodes", List.of(
                            Map.of("id", "in", "type", "IO"),
                            Map.of("id", "llm", "type", "LLM",
                                    "config", Map.of("routingPlanId", planId)),
                            Map.of("id", "out", "type", "Output")),
                    "edges", List.of(
                            Map.of("from", "in", "to", "llm"),
                            Map.of("from", "llm", "to", "out"))));
            var version = flows.publish(draft.id());
            var run = flows.run(version.id(), Map.of("prompt", "hello flow gateway"));

            assertEquals("COMPLETED", run.status(), "run=" + run);
            assertNotNull(run.output());

            usageRecorder.flush();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT purpose, status, session_id, turn_id, prompt_tokens, completion_tokens
                    FROM model_usage
                    WHERE workspace_id = ? AND purpose = 'FLOW'
                    """,
                    ws);
            assertFalse(rows.isEmpty(), "Flow LLM 须经 ModelGateway 记账 FLOW: " + rows);
            assertTrue(rows.stream().allMatch(r -> "SUCCESS".equals(r.get("status"))),
                    "FLOW usage 应为 SUCCESS: " + rows);
            assertTrue(rows.stream().anyMatch(r -> run.id().equals(r.get("session_id"))),
                    "usage.session_id 应为 flowRunId: " + rows);
            assertTrue(rows.stream().anyMatch(r -> r.get("turn_id") != null
                            && String.valueOf(r.get("turn_id")).startsWith("fnr")),
                    "usage.turn_id 应为 nodeRunId(fnr_*): " + rows);

            long used = quotaGuard.usedTokens(QuotaMetrics.SCOPE_WORKSPACE, ws);
            assertTrue(used > 0, "FLOW 调用须计入 workspace tokens_month，used=" + used);

            long promptSum = rows.stream()
                    .mapToLong(r -> ((Number) r.get("prompt_tokens")).longValue()).sum();
            long completionSum = rows.stream()
                    .mapToLong(r -> ((Number) r.get("completion_tokens")).longValue()).sum();
            assertEquals(promptSum + completionSum, used,
                    "配额 used 应等于 FLOW usage token 合计");
            return null;
        });
    }

    @Test
    void legacyDirectConfigMapsToDefaultPlan() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            String planId = seedChatPlan();
            upsertFlowDefaultPlan(planId);

            String versionId = Ids.newId("fv");
            String graphJson = """
                    {"nodes":[{"id":"n1","type":"LLM","config":{"baseUrl":"mock://openai","apiKey":"sk-legacy","model":"gpt"}}],"edges":[]}
                    """;
            jdbc.update(
                    """
                    INSERT INTO flow_version(id, draft_id, name, graph_json, published, create_at, create_by)
                    VALUES (?, ?, ?, ?, TRUE, now(), 'test')
                    """,
                    versionId, Ids.newId("fd"), "legacy-llm", graphJson);

            var run = flows.run(versionId, Map.of("prompt", "legacy"));
            assertEquals("COMPLETED", run.status(), "legacy run=" + run);

            usageRecorder.flush();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT purpose, status FROM model_usage WHERE workspace_id = ? AND purpose = 'FLOW'",
                    ws);
            assertFalse(rows.isEmpty(), "旧直连快照加载后仍须走网关记账: " + rows);
            return null;
        });
    }

    private void upsertFlowDefaultPlan(String planId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_default_plan WHERE purpose = 'FLOW'", Integer.class);
        if (exists != null && exists > 0) {
            jdbc.update(
                    "UPDATE model_default_plan SET routing_plan_id = ?, update_at = now(), update_by = 'test' WHERE purpose = 'FLOW'",
                    planId);
        } else {
            jdbc.update(
                    "INSERT INTO model_default_plan(purpose, routing_plan_id, update_at, update_by) VALUES ('FLOW', ?, now(), 'test')",
                    planId);
        }
    }

    private String seedChatPlan() {
        var provider = models.createProvider(new CreateProviderRequest(Ids.newId("p"), "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "chat"));
        String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-flow"));
        String planId = models.createPlan(
                new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK", binding, null, null)).id();
        models.publish(planId);
        return planId;
    }

    private void clearTok(String workspaceId, String userId) {
        String ym = DefaultQuotaGuard.yearMonth();
        redis.delete(List.of(
                quotaGuard.tokKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym),
                quotaGuard.tokKey(QuotaMetrics.SCOPE_USER, userId, ym)));
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
    }
}

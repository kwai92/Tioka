package com.tioka.server.model;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-05：用量统一上报。见 M1-03 MH-05。
 */
@Tag("it")
class UsageIT {

    @Test
    void everyCallRecorded() {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(), false)) {
            Seeded seeded = seedPlan(di);
            ModelGateway gateway = di.a().getBean(ModelGateway.class);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());

            principals.runAs(p, () -> {
                gateway.chat(
                        new ModelCallContext(p, seeded.threadId(), Ids.newId("turn"), null,
                                seeded.planId(), ModelPurpose.CHAT),
                        new ModelGateway.ChatRequest(
                                List.of(Map.of("role", "user", "content", "ping")),
                                List.of(), null, null),
                        d -> {});
                gateway.chat(
                        new ModelCallContext(p, Ids.newId("flow"), Ids.newId("node"), null,
                                seeded.planId(), ModelPurpose.FLOW),
                        new ModelGateway.ChatRequest(
                                List.of(Map.of("role", "user", "content", "flow")),
                                List.of(), null, null),
                        d -> {});
                gateway.embed(
                        new ModelCallContext(p, null, null, null, null, ModelPurpose.EMBEDDING),
                        new ModelGateway.EmbeddingRequest(seeded.embeddingBindingId(), List.of("hello embedding")));
                return null;
            });

            di.a().getBean(UsageRecorder.class).flush();

            List<Map<String, Object>> rows = di.a().getBean(JdbcTemplate.class).queryForList(
                    "SELECT purpose, status FROM model_usage WHERE workspace_id = ? ORDER BY create_at, id",
                    seeded.workspaceId());
            assertTrue(rows.stream().anyMatch(r -> "CHAT".equals(r.get("purpose"))
                    && "SUCCESS".equals(r.get("status"))), "应有 CHAT SUCCESS: " + rows);
            assertTrue(rows.stream().anyMatch(r -> "FLOW".equals(r.get("purpose"))
                    && "SUCCESS".equals(r.get("status"))), "应有 FLOW SUCCESS: " + rows);
            assertTrue(rows.stream().anyMatch(r -> "EMBEDDING".equals(r.get("purpose"))
                    && "SUCCESS".equals(r.get("status"))), "应有 EMBEDDING SUCCESS: " + rows);
        }
    }

    @Test
    void failoverRecordedTwice() {
        try (DualInstance di = DualInstance.start("failover-before-delta",
                Map.of("tioka.model.retry.base-delay", "1ms"), false)) {
            Seeded seeded = seedPlan(di);
            ModelGateway gateway = di.a().getBean(ModelGateway.class);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());

            ModelGateway.ChatResult result = principals.runAs(p, () -> gateway.chat(
                    new ModelCallContext(p, seeded.threadId(), seeded.turnId(), null,
                            seeded.planId(), ModelPurpose.CHAT),
                    new ModelGateway.ChatRequest(
                            List.of(Map.of("role", "user", "content", "ping")),
                            List.of(), null, null),
                    d -> {}));

            assertEquals(1, result.failoverCount());
            assertEquals(seeded.fallbackBindingId(), result.bindingId());

            di.a().getBean(UsageRecorder.class).flush();

            List<Map<String, Object>> rows = di.a().getBean(JdbcTemplate.class).queryForList(
                    """
                    SELECT binding_id, status, model_ref
                    FROM model_usage
                    WHERE thread_id = ? AND turn_id = ?
                    ORDER BY create_at, id
                    """,
                    seeded.threadId(), seeded.turnId());
            assertEquals(2, rows.size(), "故障接力应写 2 条 usage: " + rows);
            assertEquals("FAILOVER", rows.get(0).get("status"));
            assertEquals(seeded.primaryBindingId(), rows.get(0).get("binding_id"));
            assertEquals("SUCCESS", rows.get(1).get("status"));
            assertEquals(seeded.fallbackBindingId(), rows.get(1).get("binding_id"));
        }
    }

    private static Seeded seedPlan(DualInstance di) {
        PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
        ModelAdminService models = di.a().getBean(ModelAdminService.class);

        GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
        return principals.runAs(GateSeed.user(login.userId(), login.workspaceId()), () -> {
            var provider = models.createProvider(
                    new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
            String primary = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-primary"));
            String fallback = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-fallback"));
            String embed = models.createModelBinding(
                    new CreateModelBindingRequest(lm, provider.id(), "text-embedding", "embedding", 32));
            String planId = models.createPlan(
                    new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK", primary, fallback, null)).id();
            models.publish(planId);
            return new Seeded(login.userId(), login.workspaceId(), planId, primary, fallback, embed,
                    Ids.newId("sess"), Ids.newId("turn"));
        });
    }

    private record Seeded(String userId, String workspaceId, String planId,
                          String primaryBindingId, String fallbackBindingId, String embeddingBindingId,
                          String threadId, String turnId) {}
}

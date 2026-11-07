package com.tioka.server.model;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-02：自动故障接力（含流式中途）。见 M1-03 MH-02。
 */
@Tag("it")
class FailoverIT {

    private static final Map<String, Object> FAST_RETRY = Map.of(
            "tioka.model.retry.base-delay", "1ms",
            "tioka.model.retry.budget", "5s");

    @Test
    void beforeFirstDelta() {
        try (DualInstance di = DualInstance.start("failover-before-delta", FAST_RETRY, false)) {
            Seeded seeded = seedWithFallback(di);
            List<ModelGateway.StreamDelta> deltas = new ArrayList<>();
            ModelGateway.ChatResult r = chat(di, seeded, deltas);

            assertTrue(deltas.stream().noneMatch(ModelGateway.StreamDelta::discarded),
                    "首 delta 前失败不应发 discarded");
            assertEquals("failover-before-ok", r.content());
            assertEquals(seeded.fallbackBindingId(), r.bindingId());
            assertEquals(1, r.failoverCount());
            assertEquals(1, freezeFailoverCount(di, seeded));
        }
    }

    @Test
    void midStream() {
        try (DualInstance di = DualInstance.start("failover-mid-stream", FAST_RETRY, false)) {
            Seeded seeded = seedWithFallback(di);
            List<ModelGateway.StreamDelta> deltas = new ArrayList<>();
            AtomicInteger discardAt = new AtomicInteger(-1);
            ModelGateway.ChatResult r = chat(di, seeded, d -> {
                if (d.discarded()) {
                    discardAt.set(deltas.size());
                }
                deltas.add(d);
            });

            assertTrue(discardAt.get() >= 0, "流式中途应收到 discarded");
            assertTrue(discardAt.get() > 0, "discarded 前应已有增量");
            String rebuilt = deltas.stream()
                    .skip(discardAt.get() + 1L)
                    .filter(d -> !d.discarded() && d.textDelta() != null)
                    .map(ModelGateway.StreamDelta::textDelta)
                    .reduce("", String::concat);
            assertEquals("failover-complete", r.content());
            assertEquals("failover-complete", rebuilt, "最终文本只来自备用、无与主模型片段拼接");
            assertFalse(r.content().contains("primary-partial"), "不得残留主模型片段");
            assertEquals(seeded.fallbackBindingId(), r.bindingId());
            assertEquals(1, r.failoverCount());
        }
    }

    @Test
    void nonRetryable400NoFailover() {
        try (DualInstance di = DualInstance.start("failover-400", Map.of(), false)) {
            Seeded seeded = seedWithFallback(di);
            BizException ex = assertThrows(BizException.class, () -> chat(di, seeded, new ArrayList<>()));
            assertEquals(ErrorCode.MODEL_ERROR, ex.getErrorCode());
            assertEquals(seeded.primaryBindingId(),
                    di.a().getBean(JdbcTemplate.class).queryForObject(
                            "SELECT binding_id FROM model_turn_freeze WHERE session_id = ? AND turn_id = ?",
                            String.class, seeded.sessionId(), seeded.turnId()),
                    "400 不得切换冻结 binding");
            assertEquals(0, freezeFailoverCount(di, seeded));
        }
    }

    @Test
    void chainExhausted503() {
        try (DualInstance di = DualInstance.start("failover-exhausted", FAST_RETRY, false)) {
            Seeded seeded = seedWithFallback(di);
            BizException ex = assertThrows(BizException.class, () -> chat(di, seeded, new ArrayList<>()));
            assertEquals(ErrorCode.MODEL_UNAVAILABLE, ex.getErrorCode());
        }
    }

    private static ModelGateway.ChatResult chat(DualInstance di, Seeded seeded,
                                                List<ModelGateway.StreamDelta> deltas) {
        return chat(di, seeded, deltas::add);
    }

    private static ModelGateway.ChatResult chat(DualInstance di, Seeded seeded,
                                                java.util.function.Consumer<ModelGateway.StreamDelta> onDelta) {
        ModelGateway gateway = di.a().getBean(ModelGateway.class);
        PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
        Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());
        return principals.runAs(p, () -> gateway.chat(
                new ModelCallContext(p, seeded.sessionId(), seeded.turnId(), null,
                        seeded.planId(), ModelPurpose.CHAT),
                new ModelGateway.ChatRequest(
                        List.of(Map.of("role", "user", "content", "ping")),
                        List.of(), null, null),
                onDelta));
    }

    private static int freezeFailoverCount(DualInstance di, Seeded seeded) {
        Integer n = di.a().getBean(JdbcTemplate.class).queryForObject(
                "SELECT failover_count FROM model_turn_freeze WHERE session_id = ? AND turn_id = ?",
                Integer.class, seeded.sessionId(), seeded.turnId());
        return n == null ? 0 : n;
    }

    private static Seeded seedWithFallback(DualInstance di) {
        PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
        ModelAdminService models = di.a().getBean(ModelAdminService.class);
        ModelEntities entities = di.a().getBean(ModelEntities.class);

        // 借 GateSeed 登录与 workspace
        GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
        return principals.runAs(GateSeed.user(login.userId(), login.workspaceId()), () -> {
            var provider = models.createProvider(
                    new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
            String primary = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-primary"));
            String fallback = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-fallback"));
            String planId = models.createPlan(
                    new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK", primary, fallback, null)).id();
            models.publish(planId);

            // 确保 fallback_chain 落库（createPlan 经 8 参构造写入 fallbackBindingId→chain）
            ModelEntities.RoutingPlan plan = entities.getPlan(planId).orElseThrow();
            assertNotNull(plan.fallbackBindingId());

            String sessionId = Ids.newId("sess");
            String turnId = Ids.newId("turn");
            return new Seeded(login.userId(), login.workspaceId(), planId, primary, fallback, sessionId, turnId);
        });
    }

    private record Seeded(String userId, String workspaceId, String planId,
                          String primaryBindingId, String fallbackBindingId,
                          String sessionId, String turnId) {}
}

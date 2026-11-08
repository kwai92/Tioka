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
import com.tioka.model.application.gateway.ModelBindingCooldownService;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-03：冷却窗口与 resolve 跳过。见 M1-03 CooldownIT。
 */
@Tag("it")
class CooldownIT {

    @Test
    void fiveFailuresTripCooldownAndNewTurnUsesFallback() {
        try (DualInstance di = DualInstance.start("cooldown-fail",
                Map.of(
                        "tioka.model.retry.max-attempts", "0",
                        "tioka.model.cooldown.failures", "5",
                        "tioka.model.cooldown.window", "60s",
                        "tioka.model.cooldown.duration", "60s"),
                false)) {
            injectFailScript(di);
            Seeded seeded = seedWithFallback(di);
            ModelBindingCooldownService cooldown = di.a().getBean(ModelBindingCooldownService.class);
            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            redis.delete(List.of(
                    ModelBindingCooldownService.cooldownKey(seeded.primaryBindingId()),
                    ModelBindingCooldownService.failKey(seeded.primaryBindingId())));

            // 直接打满失败计数（绕过脚本，保证阈值精确）
            for (int i = 0; i < 5; i++) {
                cooldown.recordFailure(seeded.primaryBindingId());
            }
            assertTrue(cooldown.isCooling(seeded.primaryBindingId()), "5 次失败后应进入冷却");
            assertEquals("1", redis.opsForValue().get(
                    ModelBindingCooldownService.cooldownKey(seeded.primaryBindingId())));

            // 新 Turn：resolve 应跳过主 binding，冻结写入备用
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            ModelBindingResolver resolver = di.a().getBean(ModelBindingResolver.class);
            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());
            String threadId = Ids.newId("sess");
            String turnId = Ids.newId("turn");
            ModelBindingResolver.Resolution sel = principals.runAs(p, () ->
                    resolver.resolve(new ModelCallContext(p, threadId, turnId, null,
                            seeded.planId(), ModelPurpose.CHAT)));

            assertEquals(seeded.fallbackBindingId(), sel.bindingId(),
                    "冷却期间新 Turn 应选备用");
            String frozen = di.a().getBean(JdbcTemplate.class).queryForObject(
                    "SELECT binding_id FROM model_turn_freeze WHERE thread_id = ? AND turn_id = ?",
                    String.class, threadId, turnId);
            assertEquals(seeded.fallbackBindingId(), frozen, "冻结应写入备用 binding");
        }
    }

    @Test
    void gatewayFailuresAccumulateTowardCooldown() {
        try (DualInstance di = DualInstance.start("cooldown-fail",
                Map.of("tioka.model.retry.max-attempts", "0"), false)) {
            injectFailScript(di);
            Seeded seeded = seedWithFallback(di);
            ModelBindingCooldownService cooldown = di.a().getBean(ModelBindingCooldownService.class);
            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            redis.delete(List.of(
                    ModelBindingCooldownService.cooldownKey(seeded.primaryBindingId()),
                    ModelBindingCooldownService.failKey(seeded.primaryBindingId())));

            ModelGateway gateway = di.a().getBean(ModelGateway.class);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());

            // 无备用链的硬失败路径：用单 binding 方案制造 ERROR
            String hardPlan = principals.runAs(p, () -> {
                ModelAdminService models = di.a().getBean(ModelAdminService.class);
                String id = models.createPlan(new CreateRoutingPlanRequest(
                        Ids.newId("rp"), "HARD", seeded.primaryBindingId(),
                        null, seeded.primaryBindingId())).id();
                models.publish(id);
                return id;
            });

            for (int i = 0; i < 5; i++) {
                try {
                    principals.runAs(p, () -> gateway.chat(
                            new ModelCallContext(p, Ids.newId("s"), Ids.newId("t"), null,
                                    hardPlan, ModelPurpose.CHAT),
                            new ModelGateway.ChatRequest(
                                    List.of(Map.of("role", "user", "content", "x")),
                                    List.of(), null, null),
                            d -> {}));
                } catch (RuntimeException ignored) {
                    // 预期失败
                }
            }
            assertTrue(cooldown.isCooling(seeded.primaryBindingId()),
                    "网关连续失败应触发冷却");
        }
    }

    private static void injectFailScript(DualInstance di) {
        LlmStubServer.Script script = new LlmStubServer.Script();
        script.loop = true;
        LlmStubServer.Call call = new LlmStubServer.Call();
        call.fail = new LlmStubServer.FailSpec();
        call.fail.status = 503;
        call.fail.after_chars = 0;
        script.calls = new ArrayList<>(List.of(call));
        di.llm().putScript("cooldown-fail", script);
    }

    private static Seeded seedWithFallback(DualInstance di) {
        PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
        ModelAdminService models = di.a().getBean(ModelAdminService.class);
        GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
        return principals.runAs(GateSeed.user(login.userId(), login.workspaceId()), () -> {
            var provider = models.createProvider(
                    new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
            String primary = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-primary"));
            String fallback = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-fallback"));
            String planId = models.createPlan(
                    new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK", primary, fallback, null)).id();
            models.publish(planId);
            return new Seeded(login.userId(), login.workspaceId(), planId, primary, fallback);
        });
    }

    private record Seeded(String userId, String workspaceId, String planId,
                          String primaryBindingId, String fallbackBindingId) {}
}

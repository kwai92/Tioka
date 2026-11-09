package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import com.tioka.model.ModelTestSupport.InMemoryRoutingTelemetryRepository;
import com.tioka.model.ModelTestSupport.MemorySecretStore;
import com.tioka.model.api.dto.ProviderDtos.CreateAliasRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.routing.Difficulty;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.routing.RoutingPlanExecutor;
import com.tioka.model.application.routing.RoutingPlanSpecParser;
import com.tioka.model.application.routing.RoutingPlanSpecSource;
import com.tioka.model.application.routing.RoutingTestHarness;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.RoutingTelemetry;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelRouteEventDO;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.QuotaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;

/**
 * MH-06：网关接入点回归——DIFFICULTY 计划经 RoutingPlanExecutor 在 chat 链路选中映射组
 * binding，路由事件落时间线；FIXED 平铺（legacy）行为不变。
 */
class RoutingGatewayIntegrationTest {

    private ModelAdminService admin;
    private ModelEntities entities;
    private InMemoryRoutingTelemetryRepository telemetryRepo;
    private DefaultModelGateway gateway;
    private String providerId;

    @BeforeEach
    void setUp() {
        InMemoryModelEntitiesRepository repo = new InMemoryModelEntitiesRepository();
        entities = new ModelEntities(repo);
        telemetryRepo = new InMemoryRoutingTelemetryRepository();
        admin = new ModelAdminService(new MemorySecretStore(), entities,
                mock(com.tioka.framework.security.PrincipalHolder.class),
                ModelTestSupport.disabledVersioning(entities));
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        providerId = provider.id();
    }

    private String newBinding(String name) {
        String alias = admin.createAlias(new CreateAliasRequest(name + "-m", "tool", null, null, null));
        return admin.createModelBinding(new CreateModelBindingRequest(alias, providerId, name));
    }

    private void seedPlan(String planId, String primaryBindingId) {
        entities.putPlan(new ModelEntities.RoutingPlan(planId, planId, "PRIMARY_FALLBACK",
                primaryBindingId, null, null, true, null, null));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RoutingPlanExecutor> providerOf(RoutingPlanExecutor executor) {
        ObjectProvider<RoutingPlanExecutor> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getIfAvailable()).thenReturn(executor);
        return p;
    }

    private ModelGateway.ChatRequest chatRequest(String text) {
        return new ModelGateway.ChatRequest(
                List.of(java.util.Map.of("role", "user", "content", text)), List.of(), null, null);
    }

    private ModelCallContext ctx(String planId) {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, "sess1", "turn1", "agent1", planId, ModelPurpose.CHAT);
    }

    private DefaultModelGateway newGateway(RoutingPlanExecutor executor) {
        ModelUsageRepository usageRepo = mock(ModelUsageRepository.class);
        Mockito.doAnswer(inv -> null).when(usageRepo).insertBatch(anyList());
        return new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                new ProviderClient(new MemorySecretStore(), mock(EgressClient.class), true, 8, new ObjectMapper()),
                entities,
                mock(ApplicationEventPublisher.class),
                new UsageRecorder(usageRepo),
                mock(QuotaGuard.class),
                mock(ModelRateLimiter.class),
                mock(ModelBindingCooldownService.class),
                new ModelBudgetGuard(usageRepo),
                2, 2, Duration.ofMillis(1), Duration.ofSeconds(1),
                providerOf(executor));
    }

    @Test
    void chatRoutesViaDifficultyPlan() {
        String soft = newBinding("soft");
        String hard = newBinding("hard");
        String planId = "rp_gw_diff";
        seedPlan(planId, soft);

        RoutingPlanSpecSource source = new RoutingTestHarness.MapSpecSource().put(planId, """
                {"mode": "DIFFICULTY",
                 "groups": {"light": ["%s"], "heavy": ["%s"]},
                 "difficultyToGroup": {"EASY": "light", "MEDIUM": "light", "HARD": "heavy"},
                 "decision": {"deciderId": "fake", "timeoutMs": 1500, "fallbackGroup": "light"},
                 "preferPreviousInGroup": true}
                """.formatted(soft, hard));
        RoutingTelemetry telemetry = new RoutingTelemetry(telemetryRepo);
        RoutingPlanExecutor executor = new RoutingPlanExecutor(
                new ModelBindingResolver(entities, List.of()), entities, telemetry, source,
                new com.tioka.model.application.routing.RoutingDeciderRegistry(null, null, null) {
                    @Override
                    public RoutingDecider get(String id) {
                        return new RoutingTestHarness.FakeDecider("fake",
                                new RoutingDecider.Decision("general", Difficulty.HARD, 0.9, "长文"));
                    }
                },
                List.of(), Duration.ofMillis(1500));

        gateway = newGateway(executor);
        ModelGateway.ChatResult result = gateway.chat(ctx(planId), chatRequest("很长的任务"), null);
        assertEquals(hard, result.bindingId(), "chat 链路按 DIFFICULTY 映射选中 heavy 组");

        // 路由事件落时间线：DECIDE + SELECT
        List<ModelRouteEventDO> events = telemetryRepo.eventsByTurn("turn1");
        assertEquals(2, events.size());
        assertEquals("DECIDE", events.get(0).getKind());
        assertEquals("SELECT", events.get(1).getKind());
        assertEquals(hard, events.get(1).getSelectedBindingId());
    }

    @Test
    void chatWithoutRoutingFallsBackToLegacyResolution() {
        String b1 = newBinding("b1");
        String planId = "rp_gw_legacy";
        seedPlan(planId, b1);
        // 路由编排未装配（getIfAvailable 返回 null）→ 既有 resolver.resolve 路径
        gateway = newGateway(null);
        ModelGateway.ChatResult result = gateway.chat(ctx(planId), chatRequest("你好"), null);
        assertEquals(b1, result.bindingId(), "无编排时回退既有解析，行为不变");
        assertEquals(0, telemetryRepo.eventsByTurn("turn1").size());
    }

    /** RoutingPlanSpecParser 口径回归：平铺 plan spec → legacy FIXED。 */
    @Test
    void flatPlanSpecParsesToLegacy() {
        var snapshot = RoutingPlanSpecParser.parse(new ObjectMapper().createObjectNode()
                .put("type", "PRIMARY_FALLBACK").put("primaryBindingId", "b1"));
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.legacy());
    }
}

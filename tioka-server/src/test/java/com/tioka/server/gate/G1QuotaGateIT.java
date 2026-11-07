package com.tioka.server.gate;

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
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.SseCollector;
import com.tioka.system.application.QuotaService;
import com.tioka.system.application.quota.DefaultQuotaGuard;
import com.tioka.system.application.quota.QuotaMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：GOV-03、MH-01、MH-02。用例说明见 docs/architecture/construction/M1-03。
 */
@Tag("gate")
class G1QuotaGateIT {

    /** 空间 token 配额用尽后下一次模型调用 429 QUOTA_EXCEEDED。 */
    @Test
    void tokenQuotaExceededRejectsNext() {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(), false)) {
            GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            clearQuotaRedis(di, login.workspaceId(), login.userId());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            QuotaService quotas = di.a().getBean(QuotaService.class);
            ModelAdminService models = di.a().getBean(ModelAdminService.class);
            ModelGateway gateway = di.a().getBean(ModelGateway.class);
            Principal p = GateSeed.user(login.userId(), login.workspaceId());

            String planId = principals.runAs(p, () -> {
                quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, login.workspaceId(),
                        QuotaMetrics.TOKENS_MONTH, 5L);
                var provider = models.createProvider(
                        new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
                String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
                String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt"));
                String id = models.createPlan(new CreateRoutingPlanRequest(Ids.newId("rp"), "FIXED", binding)).id();
                models.publish(id);
                return id;
            });

            principals.runAs(p, () -> gateway.chat(
                    new ModelCallContext(p, Ids.newId("sess"), Ids.newId("turn"), null,
                            planId, ModelPurpose.CHAT),
                    new ModelGateway.ChatRequest(
                            List.of(Map.of("role", "user", "content", "ping")),
                            List.of(), null, null),
                    d -> {}));

            BizException ex = assertThrows(BizException.class, () ->
                    principals.runAs(p, () -> gateway.chat(
                            new ModelCallContext(p, Ids.newId("sess"), Ids.newId("turn"), null,
                                    planId, ModelPurpose.CHAT),
                            new ModelGateway.ChatRequest(
                                    List.of(Map.of("role", "user", "content", "again")),
                                    List.of(), null, null),
                            d -> {})));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.getErrorCode());
            assertEquals(429, ex.getErrorCode().getHttpStatus());
            clearQuotaRedis(di, login.workspaceId(), login.userId());
        }
    }

    /** 主模型流式中途 503：前端收到 item.discarded，最终文本只来自备用模型。 */
    @Test
    void failoverMidStream() throws Exception {
        try (DualInstance di = DualInstance.start("failover-mid-stream",
                Map.of("tioka.model.retry.base-delay", "1ms"), false)) {
            GateSeed.Bundle bundle = seedAgentWithFallback(di);
            var thr = GateSeed.createThread(di.a(), bundle);

            try (SseCollector sse = SseCollector.subscribe(
                    di.baseUrlA(), bundle.token(), bundle.workspaceId(), thr.threadId(), null)) {
                var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                        "cm-g1-failover", "ping failover");
                var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
                assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

                sse.awaitEvent(RuntimeEventType.ITEM_DISCARDED.wire(), 20_000);

                List<RuntimeItemDO> messages = di.a().getBean(ItemRepository.class)
                        .listBySession(thr.threadId()).stream()
                        .filter(i -> ItemType.MESSAGE.wire().equals(i.getType()))
                        .filter(i -> i.getDiscarded() == null || !i.getDiscarded())
                        .filter(i -> "assistant".equals(i.getRole()))
                        .toList();
                assertFalse(messages.isEmpty(), "应有 assistant 最终消息");
                String content = messages.get(messages.size() - 1).getContentJson();
                assertTrue(content != null && content.contains("failover-complete"),
                        "最终文本应来自备用: " + content);
                assertFalse(content.contains("primary-partial"),
                        "不得残留主模型片段: " + content);
            }
        }
    }

    private static void clearQuotaRedis(DualInstance di, String workspaceId, String userId) {
        StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
        DefaultQuotaGuard guard = di.a().getBean(DefaultQuotaGuard.class);
        String ym = DefaultQuotaGuard.yearMonth();
        redis.delete(List.of(
                guard.tokKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym),
                guard.tokKey(QuotaMetrics.SCOPE_USER, userId, ym),
                guard.limKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, QuotaMetrics.TOKENS_MONTH),
                guard.ccKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId),
                guard.ccKey(QuotaMetrics.SCOPE_USER, userId)));
        di.a().getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(
                "DELETE FROM gov_quota WHERE (scope_type='workspace' AND scope_id=?) OR (scope_type='user' AND scope_id=?)",
                workspaceId, userId);
    }

    private static GateSeed.Bundle seedAgentWithFallback(DualInstance di) {
        // 复用 GateSeed 登录与发布，但用 PRIMARY_FALLBACK 方案
        GateSeed.Bundle base = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
        PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
        ModelAdminService models = di.a().getBean(ModelAdminService.class);
        com.tioka.agent.application.AgentService agents = di.a().getBean(com.tioka.agent.application.AgentService.class);

        return principals.runAs(GateSeed.user(base.userId(), base.workspaceId()), () -> {
            var provider = models.createProvider(
                    new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
            String primary = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-primary"));
            String fallback = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-fallback"));
            String planId = models.createPlan(
                    new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK", primary, fallback, null)).id();
            models.publish(planId);

            var agent = agents.create(Ids.newId("ag"), base.workspaceId());
            var spec = new com.tioka.agent.api.spec.AgentSpec(
                    1,
                    new com.tioka.agent.api.spec.AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new com.tioka.agent.api.spec.AgentSpec.Instructions(List.of(
                            new com.tioka.agent.api.spec.AgentSpec.Instructions.Section("main", "answer"))),
                    new com.tioka.agent.api.spec.AgentSpec.ModelPolicy(planId, false),
                    new com.tioka.agent.api.spec.AgentSpec.ToolSet(List.of(), List.of(), List.of(), List.of()),
                    List.of(),
                    com.tioka.agent.api.spec.AgentSpec.KnowledgeBinding.empty(),
                    com.tioka.agent.api.spec.AgentSpec.SandboxPolicy.defaults(),
                    com.tioka.agent.api.spec.AgentSpec.ApprovalSettings.empty(),
                    com.tioka.agent.api.spec.AgentSpec.Limits.defaults(),
                    null, null, null, null);
            var published = agents.publishSpec(agent.id(), spec);
            return new GateSeed.Bundle(base.userId(), base.workspaceId(), base.token(),
                    published.versionId(), planId);
        });
    }
}

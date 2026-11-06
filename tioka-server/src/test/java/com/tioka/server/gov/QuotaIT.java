package com.tioka.server.gov;

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
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.system.api.spi.QuotaGuard;
import com.tioka.system.application.QuotaService;
import com.tioka.system.application.quota.DefaultQuotaGuard;
import com.tioka.system.application.quota.QuotaFlusher;
import com.tioka.system.application.quota.QuotaMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GOV-03：配额生效。见 M1-03 GOV-03。
 */
@Tag("it")
class QuotaIT {

    @Test
    void tokensExceededRejectsNext() {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(), false)) {
            Seeded seeded = seedPlan(di);
            clearQuotaRedis(di, seeded.workspaceId(), seeded.userId());
            QuotaService quotas = di.a().getBean(QuotaService.class);
            ModelGateway gateway = di.a().getBean(ModelGateway.class);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());

            principals.runAs(p, () -> {
                quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, seeded.workspaceId(),
                        QuotaMetrics.TOKENS_MONTH, 5L);
                return null;
            });

            principals.runAs(p, () -> gateway.chat(
                    new ModelCallContext(p, seeded.sessionId(), Ids.newId("turn"), null,
                            seeded.planId(), ModelPurpose.CHAT),
                    new ModelGateway.ChatRequest(
                            List.of(Map.of("role", "user", "content", "ping")),
                            List.of(), null, null),
                    d -> {}));

            BizException ex = assertThrows(BizException.class, () ->
                    principals.runAs(p, () -> gateway.chat(
                            new ModelCallContext(p, seeded.sessionId(), Ids.newId("turn"), null,
                                    seeded.planId(), ModelPurpose.CHAT),
                            new ModelGateway.ChatRequest(
                                    List.of(Map.of("role", "user", "content", "again")),
                                    List.of(), null, null),
                            d -> {})));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.getErrorCode());
            assertEquals(429, ex.getErrorCode().getHttpStatus());
            clearQuotaRedis(di, seeded.workspaceId(), seeded.userId());
        }
    }

    @Test
    void concurrentLimitQueues() throws Exception {
        try (DualInstance di = DualInstance.start("chat-async-delay", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            QuotaService quotas = di.a().getBean(QuotaService.class);
            principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, bundle.workspaceId(),
                        QuotaMetrics.CONCURRENT_TURNS, 2L);
                return null;
            });
            var thr1 = GateSeed.createThread(di.a(), bundle);
            var thr2 = GateSeed.createThread(di.a(), bundle);
            var thr3 = GateSeed.createThread(di.a(), bundle);
            var h1 = GateSeed.startTurn(di.a(), bundle, thr1.threadId(), "cm-q1", "hi-1");
            var h2 = GateSeed.startTurn(di.a(), bundle, thr2.threadId(), "cm-q2", "hi-2");
            var h3 = GateSeed.startTurn(di.a(), bundle, thr3.threadId(), "cm-q3", "hi-3");

            GateSeed.awaitStatus(di.a(), bundle, h1.turnId(), 15_000, TurnStatus.RUNNING);
            GateSeed.awaitStatus(di.a(), bundle, h2.turnId(), 15_000, TurnStatus.RUNNING);

            // 第 3 个应仍 QUEUED（并发上限 2）
            long deadline = System.currentTimeMillis() + 3_000;
            TurnStatus s3 = null;
            while (System.currentTimeMillis() < deadline) {
                s3 = principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                        di.a().getBean(RuntimeApi.class).getTurn(h3.turnId()).status());
                if (s3 == TurnStatus.QUEUED) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(TurnStatus.QUEUED, s3, "第 3 个 Turn 应排队等待并发槽");

            GateSeed.awaitTerminal(di.a(), bundle, h1.turnId(), 30_000);
            GateSeed.awaitStatus(di.a(), bundle, h3.turnId(), 20_000, TurnStatus.RUNNING, TurnStatus.COMPLETED);
            GateSeed.awaitTerminal(di.a(), bundle, h2.turnId(), 30_000);
            GateSeed.awaitTerminal(di.a(), bundle, h3.turnId(), 30_000);
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
        }
    }

    @Test
    void waitingApprovalReleasesSlot() throws Exception {
        try (DualInstance di = DualInstance.start("always-write-approval", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl(), true);
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            QuotaService quotas = di.a().getBean(QuotaService.class);
            principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, bundle.workspaceId(),
                        QuotaMetrics.CONCURRENT_TURNS, 1L);
                return null;
            });

            var thr1 = GateSeed.createThread(di.a(), bundle);
            var h1 = GateSeed.startTurn(di.a(), bundle, thr1.threadId(), "cm-wa-1", "update");
            GateSeed.awaitStatus(di.a(), bundle, h1.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);

            var thr2 = GateSeed.createThread(di.a(), bundle);
            var h2 = GateSeed.startTurn(di.a(), bundle, thr2.threadId(), "cm-wa-2", "update again");
            GateSeed.awaitStatus(di.a(), bundle, h2.turnId(), 30_000, TurnStatus.WAITING_APPROVAL);
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
        }
    }

    @Test
    void redisLossRecoversFromDb() {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            QuotaService quotas = di.a().getBean(QuotaService.class);
            DefaultQuotaGuard guard = di.a().getBean(DefaultQuotaGuard.class);
            QuotaFlusher flusher = di.a().getBean(QuotaFlusher.class);
            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);
            String ws = bundle.workspaceId();

            principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () -> {
                quotas.putLimit(QuotaMetrics.SCOPE_WORKSPACE, ws, QuotaMetrics.TOKENS_MONTH, 100L);
                return null;
            });
            // 清 DB used，避免共享 PG 上一次用例残留
            jdbc.update("""
                    UPDATE gov_quota SET used_value = 0
                     WHERE scope_type='workspace' AND scope_id=? AND metric='tokens_month'
                    """, ws);

            QuotaGuard q = di.a().getBean(QuotaGuard.class);
            q.consumeTokens(ws, bundle.userId(), null, 80);
            flusher.flush();

            Long dbUsed = jdbc.queryForObject(
                    "SELECT used_value FROM gov_quota WHERE scope_type='workspace' AND scope_id=? AND metric='tokens_month'",
                    Long.class, ws);
            assertEquals(80L, dbUsed);

            String key = guard.tokKey(QuotaMetrics.SCOPE_WORKSPACE, ws, DefaultQuotaGuard.yearMonth());
            redis.delete(key);
            assertNull(redis.opsForValue().get(key));

            // 恢复后 used=80 < 100，预检通过
            assertDoesNotThrow(() -> q.precheckTokens(ws, bundle.userId(), null));
            assertEquals(80L, guard.usedTokens(QuotaMetrics.SCOPE_WORKSPACE, ws));

            q.consumeTokens(ws, bundle.userId(), null, 20);
            flusher.flush();
            // used=100 ≥ limit → 下一次预检拒绝
            BizException ex = assertThrows(BizException.class, () -> q.precheckTokens(ws, bundle.userId(), null));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.getErrorCode());
            clearQuotaRedis(di, bundle.workspaceId(), bundle.userId());
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
            String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt"));
            String planId = models.createPlan(
                    new CreateRoutingPlanRequest(Ids.newId("rp"), "FIXED", binding)).id();
            models.publish(planId);
            return new Seeded(login.userId(), login.workspaceId(), planId, Ids.newId("sess"));
        });
    }

    /** 共享 Testcontainers Redis / PG 会跨用例残留计数与上限。 */
    private static void clearQuotaRedis(DualInstance di, String workspaceId, String userId) {
        StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
        DefaultQuotaGuard guard = di.a().getBean(DefaultQuotaGuard.class);
        String ym = DefaultQuotaGuard.yearMonth();
        redis.delete(List.of(
                guard.tokKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym),
                guard.tokKey(QuotaMetrics.SCOPE_USER, userId, ym),
                guard.limKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, QuotaMetrics.TOKENS_MONTH),
                guard.limKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, QuotaMetrics.CONCURRENT_TURNS),
                guard.limKey(QuotaMetrics.SCOPE_USER, userId, QuotaMetrics.TOKENS_MONTH),
                guard.limKey(QuotaMetrics.SCOPE_USER, userId, QuotaMetrics.CONCURRENT_TURNS),
                guard.ccKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId),
                guard.ccKey(QuotaMetrics.SCOPE_USER, userId)));
        di.a().getBean(JdbcTemplate.class).update(
                "DELETE FROM gov_quota WHERE (scope_type='workspace' AND scope_id=?) OR (scope_type='user' AND scope_id=?)",
                workspaceId, userId);
    }

    private record Seeded(String userId, String workspaceId, String planId, String sessionId) {}
}

package com.tioka.server.model;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-01：轮次级冻结。同一 Turn 内复用 binding；新 Turn 重新选模；并发写入以先写者为准。
 */
@Tag("it")
class TurnFreezeIT {

    @Test
    void sameTurnSameBinding() {
        try (DualInstance di = DualInstance.start("three-tools", java.util.Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);
            RuntimeApi.TurnHandle handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-freeze-same", "请依次调用 echo 三次");
            RuntimeApi.TurnView done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);
            String frozenBinding = jdbc.queryForObject(
                    "SELECT binding_id FROM model_turn_freeze WHERE thread_id = ? AND turn_id = ?",
                    String.class, thr.threadId(), handle.turnId());
            assertNotNull(frozenBinding);

            String planPrimary = jdbc.queryForObject(
                    "SELECT primary_binding_id FROM model_routing_plan WHERE id = ?",
                    String.class, bundle.routingPlanId());
            assertEquals(planPrimary, frozenBinding);

            ModelBindingResolver resolver = di.a().getBean(ModelBindingResolver.class);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            Principal p = GateSeed.user(bundle.userId(), bundle.workspaceId());
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ModelBindingResolver.Resolution r = principals.runAs(p, () -> resolver.resolve(
                        new ModelCallContext(p, thr.threadId(), handle.turnId(), null,
                                bundle.routingPlanId(), ModelPurpose.CHAT)));
                ids.add(r.bindingId());
                assertEquals("frozen", r.reason());
            }
            assertEquals(Set.of(frozenBinding), Set.copyOf(ids), "同 Turn 三次 resolve 应命中同一冻结");
        }
    }

    @Test
    void newTurnReselects() {
        try (DualInstance di = DualInstance.start("slow-pong", java.util.Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            ModelAdminService models = di.a().getBean(ModelAdminService.class);
            ModelEntities entities = di.a().getBean(ModelEntities.class);
            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);

            String b1 = jdbc.queryForObject(
                    "SELECT primary_binding_id FROM model_routing_plan WHERE id = ?",
                    String.class, bundle.routingPlanId());
            String providerId = jdbc.queryForObject(
                    "SELECT provider_id FROM model_binding WHERE id = ?", String.class, b1);
            String lmId = jdbc.queryForObject(
                    "SELECT logical_model_id FROM model_binding WHERE id = ?", String.class, b1);

            String b2 = principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                    models.createModelBinding(new CreateModelBindingRequest(lmId, providerId, "gpt-stub-b2")));

            RuntimeApi.ThreadView thr = GateSeed.createThread(di.a(), bundle);
            RuntimeApi.TurnHandle t1 = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-freeze-t1", "ping");
            RuntimeApi.TurnView done1 = GateSeed.awaitTerminal(di.a(), bundle, t1.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done1.status(), () -> done1.errorMessage());
            assertEquals(b1, jdbc.queryForObject(
                    "SELECT binding_id FROM model_turn_freeze WHERE thread_id = ? AND turn_id = ?",
                    String.class, thr.threadId(), t1.turnId()));

            // 中途改方案主模型；下一 Turn 应重新选模
            ModelEntities.RoutingPlan plan = entities.getPlan(bundle.routingPlanId()).orElseThrow();
            entities.putPlan(new ModelEntities.RoutingPlan(
                    plan.id(), plan.name(), plan.type(), b2,
                    plan.fallbackBindingId(), plan.hardBindingId(),
                    plan.published(), plan.snapshotHash(), plan.fallbackChain()));

            RuntimeApi.TurnHandle t2 = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-freeze-t2", "ping again");
            RuntimeApi.TurnView done2 = GateSeed.awaitTerminal(di.a(), bundle, t2.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done2.status(), () -> done2.errorMessage());
            assertEquals(b2, jdbc.queryForObject(
                    "SELECT binding_id FROM model_turn_freeze WHERE thread_id = ? AND turn_id = ?",
                    String.class, thr.threadId(), t2.turnId()));
            assertNotEquals(b1, b2);
        }
    }

    @Test
    void concurrentFreezeSingleWinner() throws Exception {
        try (DualInstance di = DualInstance.start("slow-pong", java.util.Map.of(), false)) {
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            ModelAdminService models = di.a().getBean(ModelAdminService.class);
            ModelEntities entities = di.a().getBean(ModelEntities.class);

            GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            List<String> bindingIds = principals.runAs(
                    GateSeed.user(login.userId(), login.workspaceId()), () -> {
                        var provider = models.createProvider(
                                new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
                        String lm = models.createLogicalModel(
                                new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
                        List<String> ids = new ArrayList<>();
                        for (int i = 0; i < 8; i++) {
                            ids.add(models.createModelBinding(
                                    new CreateModelBindingRequest(lm, provider.id(), "m-" + i)));
                        }
                        return ids;
                    });

            String threadId = Ids.newId("sess");
            String turnId = Ids.newId("turn");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(bindingIds.size());
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (String bid : bindingIds) {
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return entities.insertFreezeIfAbsent(threadId, turnId, bid, "race");
                    }));
                }
                start.countDown();
                Set<String> winners = java.util.concurrent.ConcurrentHashMap.newKeySet();
                for (Future<String> f : futures) {
                    winners.add(f.get(30, TimeUnit.SECONDS));
                }
                assertEquals(1, winners.size(), "并发写入只能有一个赢家: " + winners);
                assertEquals(winners.iterator().next(),
                        entities.getFreeze(threadId, turnId).orElseThrow());
            } finally {
                pool.shutdownNow();
            }
        }
    }
}

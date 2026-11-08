package com.tioka.server.orch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.mapper.OrchEventMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.receipt.OrchTimerDO;
import com.tioka.orchestration.receipt.OrchTimerMapper;
import com.tioka.orchestration.timer.OrchTimerService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ORC-06 / PK-20：定时与延时。
 */
@SpringBootTest
@Tag("it")
@Import(TestFixtures.class)
class OrchTimerIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.orch.timer-poll-enabled", () -> "false");
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired OrchTimerService timerService;
    @Autowired OrchTimerMapper timers;
    @Autowired OrchEventMapper events;
    @Autowired OrchRuleVersionMapper versions;
    @Autowired OrchApi orchApi;

    @Test
    void laterSameKeyReschedules() {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        principals.runAs(principal(actor), () -> {
            String wi = Ids.newId("wi");
            String key = "later-" + UUID.randomUUID().toString().substring(0, 8);
            LocalDateTime t1 = LocalDateTime.now().plusHours(2);
            LocalDateTime t2 = LocalDateTime.now().plusHours(5);
            OrchTimerDO a = timerService.createLater(actor.workspaceId(), wi, key, t1,
                    Map.of("subjectKey", "subj", "eventType", "orch.later"));
            OrchTimerDO b = timerService.createLater(actor.workspaceId(), wi, key, t2,
                    Map.of("subjectKey", "subj", "eventType", "orch.later"));
            assertEquals(a.getId(), b.getId());
            assertEquals(t2.withNano(0), b.getNextFireAt().withNano(0));
            long count = timers.selectCount(Wrappers.<OrchTimerDO>lambdaQuery()
                    .eq(OrchTimerDO::getWorkItemId, wi)
                    .eq(OrchTimerDO::getTimerKey, key));
            assertEquals(1L, count);
            return null;
        });
    }

    @Test
    void survivesRestart() {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String token = UUID.randomUUID().toString().substring(0, 8);
        seedRule(actor, "demo.timer." + token);
        String idemPrefix = principals.runAs(principal(actor), () -> {
            OrchTimerDO row = timerService.createLater(
                    actor.workspaceId(),
                    Ids.newId("wi"),
                    "surv-" + token,
                    LocalDateTime.now().minusSeconds(1),
                    Map.of(
                            "subjectKey", "subj-surv-" + token,
                            "eventType", "demo.timer." + token,
                            "attrs", Map.of("channel", "feishu", "tioka.addressed", "true")));
            int fired = timerService.pollOnce();
            assertTrue(fired >= 1);
            OrchTimerDO after = timers.selectById(row.getId());
            assertEquals("DONE", after.getStatus());
            return row.getId();
        });
        // 「重启」：再 poll 一次不得重复写入同 idem（已 DONE，claim 不到）
        principals.runAs(principal(actor), () -> {
            int again = timerService.pollOnce();
            assertEquals(0, again);
            long stored = events.selectCount(Wrappers.<OrchEventDO>lambdaQuery()
                    .likeRight(OrchEventDO::getIdempotencyKey, idemPrefix + ":"));
            assertEquals(1L, stored);
            return null;
        });
    }

    @Test
    void noDoubleFireAcrossInstances() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String token = UUID.randomUUID().toString().substring(0, 8);
        seedRule(actor, "demo.timer.mw." + token);
        String timerId = principals.runAs(principal(actor), () ->
                timerService.createLater(
                        actor.workspaceId(),
                        Ids.newId("wi"),
                        "mw-" + token,
                        LocalDateTime.now().minusSeconds(1),
                        Map.of(
                                "subjectKey", "subj-mw-" + token,
                                "eventType", "demo.timer.mw." + token,
                                "attrs", Map.of("channel", "feishu", "tioka.addressed", "true"))).getId());

        AtomicInteger totalFired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    principals.runAs(principal(actor), () -> {
                        totalFired.addAndGet(timerService.pollOnce());
                        return null;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, totalFired.get());
        principals.runAs(principal(actor), () -> {
            OrchTimerDO after = timers.selectById(timerId);
            assertEquals("DONE", after.getStatus());
            long stored = events.selectCount(Wrappers.<OrchEventDO>lambdaQuery()
                    .likeRight(OrchEventDO::getIdempotencyKey, timerId + ":"));
            assertEquals(1L, stored);
            return null;
        });
    }

    @Test
    void misfirePolicies() {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String token = UUID.randomUUID().toString().substring(0, 8);
        seedRule(actor, "demo.timer.mf." + token);
        principals.runAs(principal(actor), () -> {
            // SKIP：到期不 ingest，只推进 next
            OrchTimerDO skip = timerService.createCron(
                    actor.workspaceId(),
                    "rule-skip-" + token,
                    "0/5 * * * * *",
                    "UTC",
                    "SKIP",
                    Map.of("subjectKey", "subj-skip", "eventType", "demo.timer.mf." + token,
                            "attrs", Map.of("channel", "feishu", "tioka.addressed", "true")));
            skip.setNextFireAt(LocalDateTime.now().minusMinutes(1));
            skip.setUpdateAt(LocalDateTime.now());
            timers.updateById(skip);
            LocalDateTime before = skip.getNextFireAt();
            timerService.pollOnce();
            OrchTimerDO afterSkip = timers.selectById(skip.getId());
            assertEquals("ACTIVE", afterSkip.getStatus());
            assertTrue(afterSkip.getNextFireAt().isAfter(before));
            long skipEvents = events.selectCount(Wrappers.<OrchEventDO>lambdaQuery()
                    .likeRight(OrchEventDO::getIdempotencyKey, skip.getId() + ":"));
            assertEquals(0L, skipEvents);

            // LATEST_ONLY：触发一次并推进
            OrchTimerDO latest = timerService.createCron(
                    actor.workspaceId(),
                    "rule-latest-" + token,
                    "0/5 * * * * *",
                    "UTC",
                    "LATEST_ONLY",
                    Map.of("subjectKey", "subj-latest", "eventType", "demo.timer.mf." + token,
                            "attrs", Map.of("channel", "feishu", "tioka.addressed", "true")));
            latest.setNextFireAt(LocalDateTime.now().minusMinutes(1));
            timers.updateById(latest);
            timerService.pollOnce();
            OrchTimerDO afterLatest = timers.selectById(latest.getId());
            assertNotNull(afterLatest.getNextFireAt());
            long latestEvents = events.selectCount(Wrappers.<OrchEventDO>lambdaQuery()
                    .likeRight(OrchEventDO::getIdempotencyKey, latest.getId() + ":"));
            assertEquals(1L, latestEvents);
            return null;
        });
    }

    private void seedRule(TestFixtures.Actor actor, String eventType) {
        principals.runAs(principal(actor), () -> {
            OrchRuleVersionDO ver = new OrchRuleVersionDO();
            ver.setId(Ids.newId("rv"));
            ver.setRuleId(Ids.newId("rule"));
            ver.setVersion(1);
            ver.setEventType(eventType);
            ver.setActionsJson("[{\"type\":\"agent\",\"agentVersionId\":\"av_timer\"}]");
            ver.setConcurrency("serial");
            ver.setAcceptMode("auto");
            ver.setTell("needed");
            ver.setEmitsJson("[]");
            ver.setServiceAccountId(actor.userId());
            ver.setStage("STABLE");
            versions.insert(ver);
            return null;
        });
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }
}

package com.tioka.server.orch;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.orchestration.api.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ORC-01：多 worker 并发 claim，runId 不重复且总数正确。
 */
@SpringBootTest
@Import(TestFixtures.class)
@Tag("it")
class OrchClaimConcurrencyIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired OrchApi orch;
    @Autowired OrchRepository repo;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;

    @Test
    void fiftyRunsEightWorkersNoDuplicateClaims() throws Exception {
        TestFixtures.Actor actor = fixtures.newWorkspace();
        Principal principal = new Principal(
                actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("member"), Set.of("*"), "it");

        principals.runAs(principal, () -> {
            for (int i = 0; i < 50; i++) {
                OrchRunDO run = new OrchRunDO();
                run.setId("run_claim_" + actor.workspaceId() + "_" + i);
                run.setStatus("READY");
                run.setEventId("evt_claim_" + i);
                run.setWorkerId(null);
                run.setLeaseUntil(0L);
                repo.insertRun(run);
            }
        });

        Set<String> claimed = ConcurrentHashMap.newKeySet();
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            final String workerId = "w-" + w;
            pool.submit(() -> {
                try {
                    start.await();
                    principals.runAs(principal, () -> {
                        while (true) {
                            OrchApi.RunView view = orch.claim(workerId);
                            if (view == null) {
                                break;
                            }
                            assertTrue(claimed.add(view.id()), "重复领取 " + view.id());
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(50, claimed.size(), "本用例 READY run 应全部被领取且无重复");
    }
}

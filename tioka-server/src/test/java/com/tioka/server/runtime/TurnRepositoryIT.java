package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class TurnRepositoryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TurnRepository turns;
    @Autowired RuntimeSessionRepository sessions;

    private String sessionId;

    @BeforeEach
    void seedSession() {
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
        sessionId = Ids.newId("thr");
        RuntimeSessionDO s = new RuntimeSessionDO();
        s.setId(sessionId);
        s.setStatus("OPEN");
        s.setAgentVersionId("agent_v1");
        s.setWorkspaceId("ws_default");
        s.setOwnerId("0");
        s.setLastItemSeq(0L);
        s.setOrigin("chat");
        s.setDepth(0);
        sessions.upsert(s);
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acquireOnlyOnce() throws Exception {
        RuntimeTurnDO t = queuedTurn("cm-acq");
        turns.insert(t);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            String worker = "w" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    OptionalLong lv = turns.acquire(t.getId(), worker, Instant.now().plusSeconds(30));
                    if (lv.isPresent()) {
                        wins.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, wins.get());
    }

    @Test
    void clientMsgIdIdempotent() {
        RuntimeTurnDO t = queuedTurn("cm-idem");
        turns.insert(t);
        RuntimeTurnDO dup = queuedTurn("cm-idem");
        dup.setId(Ids.newId("turn"));
        dup.setSeq(2);
        assertThrows(DuplicateKeyException.class, () -> turns.insert(dup));
        assertEquals(t.getId(), turns.findBySessionAndClientMsgId(sessionId, "cm-idem").orElseThrow().getId());
    }

    private RuntimeTurnDO queuedTurn(String clientMsgId) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(Ids.newId("turn"));
        t.setSessionId(sessionId);
        t.setSeq(turns.nextSeq(sessionId));
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(clientMsgId);
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        return t;
    }
}

package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.application.TurnSignalService;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class CancelIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TurnRepository turns;
    @Autowired RuntimeThreadRepository sessions;
    @Autowired TurnSignalService signals;

    private String threadId;

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
        threadId = Ids.newId("thr");
        RuntimeThreadDO s = new RuntimeThreadDO();
        s.setId(threadId);
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
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancelQueued() {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(Ids.newId("turn"));
        t.setThreadId(threadId);
        t.setSeq(turns.nextSeq(threadId));
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(Ids.newId("cm"));
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        turns.insert(t);

        signals.interrupt(t.getId());

        RuntimeTurnDO after = turns.findById(t.getId()).orElseThrow();
        assertEquals("CANCELLED", after.getStatus());
        assertTrue(turns.acquire(t.getId(), "w1", java.time.Instant.now().plusSeconds(30)).isEmpty(),
                "已取消的排队 Turn 不可再 acquire 执行");
    }
}

package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.application.QueuedTurnRescanner;
import com.tioka.runtime.kernel.application.TurnScheduler;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** R-2：孤儿 QUEUED 头可被 rescanner 发现。 */
@SpringBootTest
@Tag("it")
class QueuedTurnRescannerIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TurnRepository turns;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired PrincipalHolder principals;
    @Autowired QueuedTurnRescanner rescanner;
    @Autowired TurnScheduler scheduler;

    @Test
    void findsOrphanQueuedHead() {
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");

        principals.runAs(user("10", "ws_qrs"), () -> {
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_x");
            s.setWorkspaceId("ws_qrs");
            s.setOwnerId("10");
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setSessionId(threadId);
            t.setSeq(1);
            t.setStatus(TurnStatus.QUEUED.name());
            t.setOrigin("chat");
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"ws_qrs\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(0L);
            turns.insert(t);
        });

        List<String> heads = turns.findOrphanQueuedHeads(50);
        assertTrue(heads.contains(turnId), "应发现孤儿 QUEUED: " + heads);

        // rescan 在池可接受时应能 trySubmit（可能立刻因无 agent 失败，但不应再留作孤儿头）
        assertTrue(scheduler.canAccept());
        rescanner.rescan();
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

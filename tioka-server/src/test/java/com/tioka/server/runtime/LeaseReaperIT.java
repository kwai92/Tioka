package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.application.LeaseReaper;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-06 W6：租约过期后第一步 INTERRUPTED，第二步默认续跑为 QUEUED（不再直接 FAILED）。
 */
@SpringBootTest
@Tag("it")
class LeaseReaperIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired LeaseReaper reaper;
    @Autowired TurnRepository turns;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired PrincipalHolder principals;
    @Autowired JdbcTemplate jdbc;

    @Test
    void crashedTurnRequeued() {
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");

        principals.runAs(user("10", "ws_reaper"), () -> {
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_x");
            s.setWorkspaceId("ws_reaper");
            s.setOwnerId("10");
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setSessionId(threadId);
            t.setSeq(1);
            t.setStatus(TurnStatus.RUNNING.name());
            t.setOrigin("chat");
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"ws_reaper\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(1L);
            turns.insert(t);
        });

        jdbc.update("UPDATE runtime_turn SET lease_until = now() - interval '1 minute' WHERE id = ?", turnId);

        reaper.reap();

        RuntimeTurnDO after = turns.findById(turnId).orElseThrow();
        assertEquals(TurnStatus.QUEUED.name(), after.getStatus());
        assertEquals(1, after.getRecoverCount());
        assertNull(after.getErrorCode());
        assertTrue(after.getLeaseVersion() >= 2, "续跑应 bump lease_version");
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

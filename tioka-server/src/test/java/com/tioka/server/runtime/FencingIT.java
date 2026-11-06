package com.tioka.server.runtime;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class FencingIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired ItemRepository items;
    @Autowired TurnRepository turns;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void asSystem() {
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void staleWorkerWritesFail() {
        String sessionId = Ids.newId("thr");
        String turnId = Ids.newId("turn");
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

        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(turnId);
        t.setSessionId(sessionId);
        t.setSeq(1);
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(Ids.newId("cm"));
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        turns.insert(t);
        long staleLv = turns.acquire(turnId, "worker-old", Instant.now().plusSeconds(60)).orElseThrow();

        // 模拟被接管：lease_version +1
        jdbc.update("UPDATE runtime_turn SET lease_version = lease_version + 1, worker_id = ? WHERE id = ?",
                "worker-new", turnId);

        BizException ex = assertThrows(BizException.class,
                () -> items.append(sessionId, turnId, staleLv, 0, "message", "assistant",
                        "{\"text\":\"stale\"}", null));
        assertEquals(ErrorCode.LEASE_LOST, ex.getErrorCode());

        int finished = turns.finish(turnId, staleLv, TurnStatus.COMPLETED, null, null, null, null);
        assertEquals(0, finished);
        assertEquals("RUNNING", turns.findById(turnId).orElseThrow().getStatus());
    }
}

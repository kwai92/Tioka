package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.application.TurnRunner;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-1：runAs 无效时立刻 FAILED(RUN_AS_INVALID)，不留给 LeaseReaper。
 */
@SpringBootTest
@Tag("it")
class TurnRunAsInvalidIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TurnRunner turnRunner;
    @Autowired TurnRepository turns;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired PrincipalHolder principals;

    @Test
    void invalidRunAsFailsImmediately() {
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");

        principals.runAs(user("10", "ws_ras"), () -> {
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_x");
            s.setWorkspaceId("ws_ras");
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
            // 缺 workspaceId → resolveRunAs 直接抛 BizException
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(0L);
            t.setLeaseUntil(LocalDateTime.ofInstant(Instant.now().plusSeconds(60), ZoneOffset.UTC));
            turns.insert(t);
        });

        assertEquals("{\"userId\":\"10\",\"workspaceId\":\"\",\"type\":\"USER\"}",
                turns.findById(turnId).orElseThrow().getRunAsJson());

        turnRunner.run(turnId);

        RuntimeTurnDO after = turns.findById(turnId).orElseThrow();
        assertEquals(TurnStatus.FAILED.name(), after.getStatus(),
                "status=" + after.getStatus() + " err=" + after.getErrorCode()
                        + " msg=" + after.getErrorMessage());
        assertEquals("RUN_AS_INVALID", after.getErrorCode(),
                "msg=" + after.getErrorMessage());
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

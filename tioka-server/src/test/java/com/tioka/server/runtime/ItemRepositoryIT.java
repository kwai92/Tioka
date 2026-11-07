package com.tioka.server.runtime;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class ItemRepositoryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired ItemRepository items;
    @Autowired TurnRepository turns;
    @Autowired RuntimeThreadRepository sessions;

    private String threadId;
    private String turnId;
    private long leaseVersion;

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

        RuntimeTurnDO t = new RuntimeTurnDO();
        turnId = Ids.newId("turn");
        t.setId(turnId);
        t.setThreadId(threadId);
        t.setSeq(1);
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(Ids.newId("cm"));
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        turns.insert(t);
        leaseVersion = turns.acquire(turnId, "worker-1", Instant.now().plusSeconds(60)).orElseThrow();
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appendIsGapFreeUnderSingleWriter() {
        for (int i = 0; i < 100; i++) {
            items.append(ItemWrite.append(threadId, turnId, leaseVersion, 0, "message", "assistant",
                    "{\"text\":\"" + i + "\"}", null));
        }
        List<RuntimeItemDO> list = items.listByThread(threadId);
        assertEquals(100, list.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i + 1L, list.get(i).getSeq());
        }
    }

    @Test
    void staleLeaseRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> items.append(ItemWrite.append(threadId, turnId, leaseVersion - 1, 0, "message", "assistant",
                        "{\"text\":\"x\"}", null)));
        assertEquals(ErrorCode.LEASE_LOST, ex.getErrorCode());
    }
}

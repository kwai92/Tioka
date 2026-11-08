package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.application.LeaseReaper;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-06 W6：崩溃续跑上限与丢失 tool_result 补写。
 */
@SpringBootTest
@Tag("it")
class RecoveryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.runtime.max-recover", () -> "2");
    }

    @Autowired LeaseReaper reaper;
    @Autowired TurnRepository turns;
    @Autowired ItemRepository items;
    @Autowired RuntimeThreadRepository sessions;
    @Autowired PrincipalHolder principals;
    @Autowired EventStream streams;
    @Autowired JdbcTemplate jdbc;

    @Test
    void giveUpAfterMaxRecover() {
        String threadId = Ids.newId("thr");
        String turnId = plantRunning(threadId, 0);

        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE runtime_turn SET status = 'RUNNING', lease_until = now() - interval '1 minute', "
                    + "worker_id = 'A' WHERE id = ?", turnId);
            reaper.reap();
            RuntimeTurnDO after = turns.findById(turnId).orElseThrow();
            if (i < 2) {
                assertEquals(TurnStatus.QUEUED.name(), after.getStatus(), "第 " + (i + 1) + " 次应续跑");
                assertEquals(i + 1, after.getRecoverCount());
            } else {
                assertEquals(TurnStatus.FAILED.name(), after.getStatus());
                assertEquals("LEASE_LOST", after.getErrorCode());
                assertEquals(2, after.getRecoverCount());
            }
        }
    }

    @Test
    void writesLostToolResultAndDiscardsCur() {
        String threadId = Ids.newId("thr");
        String turnId = plantRunning(threadId, 0);

        principals.runAs(user("10", "ws_rec"), () -> {
            items.appendWithoutLease(ItemWrite.withoutLease(threadId, turnId, 0, ItemType.TOOL_CALL.wire(), "assistant",
                    "{\"callId\":\"c1\",\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}"));
        });

        String entry = streams.publish(threadId, "99.0", "item.created",
                "{\"schemaVersion\":1,\"type\":\"item.created\",\"threadId\":\"" + threadId
                        + "\",\"turnId\":\"" + turnId
                        + "\",\"ts\":\"2026-11-06T00:00:00Z\","
                        + "\"item\":{\"itemId\":\"itm_cur\",\"seq\":99,\"itemType\":\"message\"}}");
        streams.setCurrentEntry(threadId, entry);

        jdbc.update("UPDATE runtime_turn SET lease_until = now() - interval '1 minute' WHERE id = ?", turnId);
        reaper.reap();

        RuntimeTurnDO after = turns.findById(turnId).orElseThrow();
        assertEquals(TurnStatus.QUEUED.name(), after.getStatus());
        assertEquals(1, after.getRecoverCount());
        assertNull(streams.currentEntry(threadId));

        List<RuntimeItemDO> results = items.listByThread(threadId).stream()
                .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                .toList();
        assertEquals(1, results.size());
        assertTrue(results.get(0).getContentJson().contains(LeaseReaper.LOST_TOOL_OUTPUT));
        assertTrue(results.get(0).getContentJson().contains("\"ok\":false")
                || results.get(0).getContentJson().contains("\"ok\": false"));

        List<EventStream.StreamEvent> evs = streams.range(threadId, "-", "+");
        assertTrue(evs.stream().anyMatch(e -> "item.discarded".equals(e.type())),
                "应发布 item.discarded");
    }

    private String plantRunning(String threadId, int recoverCount) {
        String turnId = Ids.newId("turn");
        principals.runAs(user("10", "ws_rec"), () -> {
            RuntimeThreadDO s = new RuntimeThreadDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_rec");
            s.setWorkspaceId("ws_rec");
            s.setOwnerId("10");
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setThreadId(threadId);
            t.setSeq(1);
            t.setStatus(TurnStatus.RUNNING.name());
            t.setOrigin("chat");
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"ws_rec\",\"type\":\"USER\"}");
            t.setStepCount(1);
            t.setLeaseVersion(1L);
            turns.insert(t);
        });
        jdbc.update("UPDATE runtime_turn SET recover_count = ?, lease_until = now() - interval '1 minute' WHERE id = ?",
                recoverCount, turnId);
        return turnId;
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

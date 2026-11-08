package com.tioka.server.auth;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class CrossUserIsolationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentRepository agents;
    @Autowired KnowledgeRepository knowledge;
    @Autowired ChatRepository chats;
    @Autowired PrincipalHolder principals;
    @Autowired RuntimeApi runtime;
    @Autowired RuntimeThreadRepository sessions;
    @Autowired TurnRepository turns;
    @Autowired SignalBus signalBus;

    @Test
    void twoUsersCannotSeeEachOther() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String agentId = Ids.newId("agt");
        String kbId = Ids.newId("kb");

        principals.runAs(user("10", wsA), () -> {
            AgentDO a = new AgentDO();
            a.setId(agentId);
            a.setName("a");
            a.setEnabled(true);
            agents.upsertAgent(a);

            KbBaseDO kb = new KbBaseDO();
            kb.setId(kbId);
            kb.setName("kb-a");
            knowledge.insertBase(kb);
            assertTrue(knowledge.hasBase(kbId));
        });

        principals.runAs(user("20", wsB), () -> {
            assertNull(agents.findAgent(agentId));
            assertTrue(agents.listAgents().stream().noneMatch(x -> agentId.equals(x.getId())));
            assertFalse(knowledge.hasBase(kbId));
        });
    }

    @Test
    void sameWorkspacePrivateConversation() {
        String ws = Ids.newId("ws");
        String convId = Ids.newId("conv");

        principals.runAs(user("10", ws), () -> {
            ChatConversationDO c = new ChatConversationDO();
            c.setId(convId);
            c.setTitle("private");
            c.setAgentVersionId("av_x");
            c.setRuntimeThreadId(Ids.newId("thr"));
            chats.insertConversation(c);
            assertNotNull(chats.findConversation(convId));
            assertTrue(chats.listConversations().stream().anyMatch(x -> convId.equals(x.getId())));
        });

        // 同空间另一用户：看得到 Agent，看不到会话
        principals.runAs(user("20", ws), () -> {
            assertNull(chats.findConversation(convId));
            assertTrue(chats.listConversations().stream().noneMatch(x -> convId.equals(x.getId())));
        });
    }

    @Test
    void interruptAndSteerOtherUsersTurnReturns404() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");

        principals.runAs(user("10", wsA), () -> seedQueuedTurn(wsA, threadId, turnId, "10"));

        BizException interruptEx = assertThrows(BizException.class,
                () -> principals.runAs(user("20", wsB), () -> runtime.interrupt(turnId)));
        assertEquals(ErrorCode.NOT_FOUND, interruptEx.getErrorCode());

        BizException steerEx = assertThrows(BizException.class,
                () -> principals.runAs(user("20", wsB), () -> runtime.steer(turnId, "hi")));
        assertEquals(ErrorCode.NOT_FOUND, steerEx.getErrorCode());

        // 越权调用不得改状态（回归：曾与 acquire 竞态导致误取消）
        assertEquals(TurnStatus.QUEUED.name(), turns.findById(turnId).orElseThrow().getStatus());

        // 所有者可以取消
        principals.runAs(user("10", wsA), () -> runtime.interrupt(turnId));
        assertEquals(TurnStatus.CANCELLED.name(), turns.findById(turnId).orElseThrow().getStatus());
    }

    /**
     * M0-L3：interrupt 与 acquire 并发时，cancelQueued CAS 失败不得静默返回。
     * 用 CyclicBarrier 对齐两线程，不加 sleep。
     */
    @Test
    void interruptWinsRaceAgainstAcquire() throws Exception {
        String ws = Ids.newId("ws");
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");
        principals.runAs(user("10", ws), () -> seedQueuedTurn(ws, threadId, turnId, "10"));

        CyclicBarrier ready = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> acquireErr = new AtomicReference<>();
        AtomicReference<Throwable> interruptErr = new AtomicReference<>();

        Thread acquirer = new Thread(() -> {
            try {
                ready.await();
                turns.acquire(turnId, "w-race", Instant.now().plusSeconds(30));
            } catch (Throwable t) {
                acquireErr.set(t);
            } finally {
                done.countDown();
            }
        }, "m0-l3-acquire");
        Thread interrupter = new Thread(() -> {
            try {
                ready.await();
                principals.runAs(user("10", ws), () -> runtime.interrupt(turnId));
            } catch (Throwable t) {
                interruptErr.set(t);
            } finally {
                done.countDown();
            }
        }, "m0-l3-interrupt");

        acquirer.start();
        interrupter.start();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS), "race threads hung");
        assertNull(acquireErr.get(), () -> "acquire: " + acquireErr.get());
        assertNull(interruptErr.get(), () -> "interrupt: " + interruptErr.get());

        RuntimeTurnDO after = turns.findById(turnId).orElseThrow();
        String status = after.getStatus();
        // interrupt 先胜：CANCELLED；acquire 先胜：RUNNING 且已写 cancel 信号
        assertTrue(
                TurnStatus.CANCELLED.name().equals(status)
                        || (TurnStatus.RUNNING.name().equals(status) && signalBus.isCancelFlag(turnId)),
                () -> "status=" + status + " cancelFlag=" + signalBus.isCancelFlag(turnId));
    }

    private void seedQueuedTurn(String ws, String threadId, String turnId, String ownerId) {
        RuntimeThreadDO s = new RuntimeThreadDO();
        s.setId(threadId);
        s.setStatus("OPEN");
        s.setAgentVersionId("av_x");
        s.setWorkspaceId(ws);
        s.setOwnerId(ownerId);
        s.setLastItemSeq(0L);
        s.setOrigin("chat");
        s.setDepth(0);
        sessions.upsert(s);

        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(turnId);
        t.setThreadId(threadId);
        t.setSeq(1);
        t.setStatus(TurnStatus.QUEUED.name());
        t.setOrigin("chat");
        t.setRunAsJson("{\"userId\":\"" + ownerId + "\",\"workspaceId\":\"" + ws + "\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        turns.insert(t);
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

package com.tioka.server.runtime;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnAwaiter;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-08a：RuntimeApi 各方法参数校验与错误码；TurnAwaiter 等待语义（M1-02）。
 */
@SpringBootTest
@Tag("it")
class RuntimeApiContractTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired ConfigurableApplicationContext ctx;
    @Autowired RuntimeApi runtime;
    @Autowired TurnAwaiter awaiter;
    @Autowired PrincipalHolder principals;
    @Autowired ItemRepository items;
    @Autowired EventStream streams;
    @Autowired RuntimeEventFactory events;
    @Autowired JdbcTemplate jdbc;

    private GateSeed.Bundle bundle;
    private Principal principal;

    @BeforeEach
    void seed() {
        bundle = GateSeed.seedPublishedAgent(ctx, "http://127.0.0.1:9");
        principal = GateSeed.user(bundle.userId(), bundle.workspaceId());
    }

    @Test
    void createThread_rejectsBlankAgentVersionId() {
        BizException ex = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd("  ", "chat", null, null, null))));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void createThread_rejectsUnknownAgentVersion() {
        BizException ex = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        "agv_missing", "chat", null, null, null))));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void startTurn_rejectsBlankClientMsgIdAndMissingThread() {
        RuntimeApi.ThreadView thr = principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "chat", null, "c", null)));

        BizException blank = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        " ", List.of(RuntimeApi.InputItem.text("hi")), null, "chat", null))));
        assertEquals(ErrorCode.BAD_REQUEST, blank.getErrorCode());

        BizException missing = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.startTurn("thr_missing", new RuntimeApi.StartTurnCmd(
                        "cm-1", List.of(RuntimeApi.InputItem.text("hi")), null, "chat", null))));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    @Test
    void startTurn_idempotentAndRateLimited() {
        RuntimeApi.ThreadView thr = principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "flow", "node-1", null,
                        java.util.Map.of("runId", "r1", "nodeId", "n1"))));

        RuntimeApi.TurnHandle first = principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-idem", List.of(RuntimeApi.InputItem.text("a")), null, "flow", "node-1")));
        RuntimeApi.TurnHandle again = principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-idem", List.of(RuntimeApi.InputItem.text("b")), null, "flow", "node-1")));
        assertEquals(first.turnId(), again.turnId());
        assertEquals(TurnStatus.QUEUED, again.status());

        for (int i = 0; i < 4; i++) {
            int n = i;
            principals.runAs(principal, () ->
                    runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                            "cm-rl-" + n, List.of(RuntimeApi.InputItem.text("x")), null, "chat", null)));
        }
        BizException limited = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-rl-overflow", List.of(RuntimeApi.InputItem.text("x")), null, "chat", null))));
        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
    }

    @Test
    void getTurn_listItems_interrupt_steer_errorCodes() {
        RuntimeApi.ThreadView thr = principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "chat", null, null, null)));
        RuntimeApi.TurnHandle handle = principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-ctl", List.of(RuntimeApi.InputItem.text("ping")), null, "chat", null)));

        BizException missingTurn = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.getTurn("turn_missing")));
        assertEquals(ErrorCode.NOT_FOUND, missingTurn.getErrorCode());

        BizException missingItems = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.listItems("thr_missing", 0, 10)));
        assertEquals(ErrorCode.NOT_FOUND, missingItems.getErrorCode());

        BizException badAfter = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.listItems(thr.threadId(), -1, 10)));
        assertEquals(ErrorCode.BAD_REQUEST, badAfter.getErrorCode());

        List<RuntimeApi.ItemView> listed = principals.runAs(principal, () ->
                runtime.listItems(thr.threadId(), 0, 0));
        assertFalse(listed.isEmpty());

        BizException blankSteer = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.steer(handle.turnId(), "  ")));
        assertEquals(ErrorCode.BAD_REQUEST, blankSteer.getErrorCode());

        principals.runAs(principal, () -> runtime.interrupt(handle.turnId()));
        RuntimeApi.TurnView cancelled = principals.runAs(principal, () -> runtime.getTurn(handle.turnId()));
        assertEquals(TurnStatus.CANCELLED, cancelled.status());

        BizException steerDone = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.steer(handle.turnId(), "late")));
        assertEquals(ErrorCode.CONFLICT, steerDone.getErrorCode());
    }

    @Test
    void decideApproval_and_fork_notImplemented() {
        BizException blankApproval = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.decideApproval(" ", new RuntimeApi.ApprovalDecision(true, null, null))));
        assertEquals(ErrorCode.BAD_REQUEST, blankApproval.getErrorCode());

        BizException approval = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.decideApproval("apv_1", new RuntimeApi.ApprovalDecision(true, null, null))));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, approval.getErrorCode());

        BizException blankFork = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.fork(" ", 0)));
        assertEquals(ErrorCode.BAD_REQUEST, blankFork.getErrorCode());

        BizException fork = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                runtime.fork("thr_x", 0)));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, fork.getErrorCode());
    }

    @Test
    void turnAwaiter_timeoutReturnsCurrentWithoutCancel() {
        RuntimeApi.ThreadView thr = principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "chat", null, null, null)));
        RuntimeApi.TurnHandle handle = principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-await-to", List.of(RuntimeApi.InputItem.text("ping")), null, "chat", null)));

        RuntimeApi.TurnView view = principals.runAs(principal, () ->
                awaiter.await(handle.turnId(), Duration.ofMillis(200)));
        assertEquals(TurnStatus.QUEUED, view.status());
        assertEquals(TurnStatus.QUEUED, principals.runAs(principal, () ->
                runtime.getTurn(handle.turnId())).status());
    }

    @Test
    void turnAwaiter_wakesOnRedisEventAndFinalText() throws Exception {
        RuntimeApi.ThreadView thr = principals.runAs(principal, () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "chat", null, null, null)));
        RuntimeApi.TurnHandle handle = principals.runAs(principal, () ->
                runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                        "cm-await-ok", List.of(RuntimeApi.InputItem.text("ping")), null, "chat", null)));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeApi.TurnView> future = pool.submit(() -> principals.runAs(principal, () ->
                    awaiter.await(handle.turnId(), Duration.ofSeconds(8))));
            Thread.sleep(80);

            String assistant = "final-from-awaiter";
            principals.runAs(principal, () ->
                    items.appendWithoutLease(thr.threadId(), handle.turnId(), 1,
                            "message", "assistant", "{\"text\":\"" + assistant + "\"}"));
            jdbc.update("UPDATE runtime_turn SET status = 'COMPLETED', ended_at = now() WHERE id = ?",
                    handle.turnId());
            streams.publish(thr.threadId(), null, RuntimeEventType.TURN_COMPLETED.wire(),
                    events.turnStatus(RuntimeEventType.TURN_COMPLETED, thr.threadId(),
                            handle.turnId(), TurnStatus.COMPLETED.name()));

            RuntimeApi.TurnView done = future.get(5, TimeUnit.SECONDS);
            assertEquals(TurnStatus.COMPLETED, done.status());
            assertEquals(Optional.of(assistant), principals.runAs(principal, () ->
                    awaiter.finalText(handle.turnId())));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void turnAwaiter_rejectsBlankTurnId() {
        BizException ex = assertThrows(BizException.class, () -> principals.runAs(principal, () ->
                awaiter.await(" ", Duration.ofSeconds(1))));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}

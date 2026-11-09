package com.tioka.infra.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.api.DeadLetterOpsController;
import com.tioka.infra.messaging.infrastructure.SysConsumedRepository;
import com.tioka.infra.messaging.infrastructure.SysDeadLetterRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORC-07（M2-00 第 75 行）死信与重放——离线用例。
 *
 * <p>生产现状（2026-11-10，main = adc51c9）：
 * <ul>
 *   <li>✅ 已实现：末次消费失败写死信——{@code IdempotentDeliveryService#deliverOnce}
 *       （120–124 行）在 {@code reconsumeTimes >= maxAttempts - 1} 时调
 *       {@code writeDeadLetter}（276–298 行）：落 {@code sys_dead_letter}，
 *       status=OPEN，attempts=重试次数+1；in-memory 侧 {@code deliverAndRetry}
 *       （96–112 行）本进程重试至 max-attempts 后末次写死信且不再抛出。</li>
 *   <li>✅ 已实现（M2-B6b）：死信查询 / 重放 / 丢弃——{@code SysDeadLetterRepository}
 *       list/findById/markReplayed/markDiscarded/markRetryFailed；{@code DeadLetterOpsService}
 *       重放走 {@code deliver}（reconsumeTimes 归零），成功 → REPLAYED，仍失败 → 保持 OPEN
 *       累加 attempts；{@code DeadLetterOpsController} 提供 {@code /api/ops/dead-letters}
 *       列表 / replay / discard（orch:operate），REST 层 mock 测试见
 *       {@code DeadLetterOpsControllerTest}。见下方 {@link ReplayAndDiscard}。</li>
 * </ul>
 *
 * <p>本类纯 JUnit5 + Mockito（无 Spring 上下文 / 无数据库 / 无 MQ broker），
 * 与模块内 {@code PluginHostIT} 同一惯例：命名 {@code *IT} 由 CI 承接，
 * 默认 surefire 构建排除。真实 broker 的重投语义（RocketMQ orderly
 * SUSPEND_CURRENT_QUEUE_A_MOMENT，见 {@code RocketMqConsumerBootstrap} 66–83 行）
 * 归容器 IT 验收。
 */
class DeadLetterReplayIT {

    private static final int MAX_ATTEMPTS = 3;

    private static final String ENVELOPE_JSON = """
            {"eventId":"evt-1","event":"demo.failed","schemaVersion":1,
             "workspaceId":"ws-1","producer":"test","payload":{"k":"v"}}
            """;

    private SysConsumedRepository consumed;
    private SysDeadLetterRepository deadLetters;
    private FailingConsumer consumer;

    /** 末次失败后不再抛出的消费者；由 {@code failures} 控制前 N 次失败。 */
    private static final class FailingConsumer extends IdempotentConsumer<Map<String, Object>> {
        final int failures;
        int attempts;

        FailingConsumer(int failures) {
            this.failures = failures;
        }

        @Override
        protected String consumerName() {
            return "test-consumer";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings("unchecked")
            Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return "tioka-runtime";
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            attempts++;
            if (attempts <= failures) {
                throw new IllegalStateException("boom-" + attempts);
            }
        }
    }

    @BeforeEach
    void setUp() {
        consumed = mock(SysConsumedRepository.class);
        when(consumed.tryMarkConsumed(any(), any())).thenReturn(true);
        deadLetters = mock(SysDeadLetterRepository.class);
        consumer = new FailingConsumer(0);
    }

    private IdempotentDeliveryService newService(FailingConsumer c) {
        PrincipalHolder principals = mock(PrincipalHolder.class);
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get())
                .when(principals).runAs(any(), any(java.util.function.Supplier.class));
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        // IdempotentDeliveryService 构造器内部以该 txManager 自建 TransactionTemplate；
        // 无真实事务管理器时回调仍被执行，断言不依赖事务边界
        MessagingProperties props = new MessagingProperties();
        props.getConsumer().setMaxAttempts(MAX_ATTEMPTS);
        return new IdempotentDeliveryService(List.of(c), consumed, deadLetters, principals,
                null, new ObjectMapper(), props, txManager);
    }

    /** RocketMQ 侧：非末次重试失败 → 异常上抛触发 broker 重投，不写死信。 */
    @Test
    void retryBeforeLastRethrowsWithoutDeadLetter() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> newService(new FailingConsumer(Integer.MAX_VALUE))
                        .deliver("test-consumer", "tioka-runtime", "demo.failed",
                                ENVELOPE_JSON, 0));
        assertTrue(e.getMessage().contains("boom"));
        verify(deadLetters, never()).insert(any());
    }

    /** RocketMQ 侧：末次重试（reconsumeTimes = max-1）失败 → 写死信、不再抛出。 */
    @Test
    void lastAttemptWritesDeadLetterAndSwallows() {
        newService(new FailingConsumer(Integer.MAX_VALUE))
                .deliver("test-consumer", "tioka-runtime", "demo.failed", ENVELOPE_JSON, MAX_ATTEMPTS - 1);

        ArgumentCaptor<SysDeadLetterDO> row = ArgumentCaptor.forClass(SysDeadLetterDO.class);
        verify(deadLetters, times(1)).insert(row.capture());
        SysDeadLetterDO dl = row.getValue();
        assertEquals("test-consumer", dl.getConsumer());
        assertEquals("tioka-runtime", dl.getTopic());
        assertEquals("demo.failed", dl.getTag());
        assertEquals("evt-1", dl.getEventId());
        assertEquals(ENVELOPE_JSON, dl.getPayloadJson());
        assertTrue(dl.getError() != null && dl.getError().contains("boom"),
                "死信应保留最后一次错误: " + dl.getError());
        assertEquals(MAX_ATTEMPTS, dl.getAttempts());
        assertEquals("OPEN", dl.getStatus());
        assertNotNull(dl.getCreateAt());
    }

    /** in-memory 侧：deliverAndRetry 本进程重试至 max-attempts，末次失败恰好写一条死信且不抛。 */
    @Test
    void inMemoryDeliverAndRetryWritesSingleDeadLetter() {
        FailingConsumer persistent = new FailingConsumer(Integer.MAX_VALUE);
        newService(persistent).deliverAndRetry(persistent, "tioka-runtime", "demo.failed", ENVELOPE_JSON);

        assertEquals(MAX_ATTEMPTS, persistent.attempts, "应恰好重试 max-attempts 次");
        ArgumentCaptor<SysDeadLetterDO> row = ArgumentCaptor.forClass(SysDeadLetterDO.class);
        verify(deadLetters, times(1)).insert(row.capture());
        assertEquals(MAX_ATTEMPTS, row.getValue().getAttempts());
        assertEquals("OPEN", row.getValue().getStatus());
    }

    /** 消费成功不产生死信。 */
    @Test
    void successNeverWritesDeadLetter() {
        FailingConsumer ok = new FailingConsumer(0);
        newService(ok).deliverAndRetry(ok, "tioka-runtime", "demo.failed", ENVELOPE_JSON);
        assertEquals(1, ok.attempts);
        verify(deadLetters, never()).insert(any());
    }

    /** 信封损坏时末次失败也写死信，eventId 记为 unknown（writeDeadLetter 282–284 行）。 */
    @Test
    void malformedPayloadGoesToDeadLetterAtLastAttempt() {
        newService(consumer).deliver("test-consumer", "tioka-runtime", "demo.failed",
                "not-a-json{", MAX_ATTEMPTS - 1);

        ArgumentCaptor<SysDeadLetterDO> row = ArgumentCaptor.forClass(SysDeadLetterDO.class);
        verify(deadLetters, times(1)).insert(row.capture());
        assertEquals("unknown", row.getValue().getEventId());
        assertEquals("OPEN", row.getValue().getStatus());
    }

    /**
     * ORC-07 死信查询 / 重放 / 丢弃（M2-B6b 实现，FE-17 后端出口）。
     *
     * <p>实现：{@code SysDeadLetterRepository} list/findById/markReplayed/
     * markDiscarded/markRetryFailed（条件更新仅 OPEN 可流转，天然幂等）；
     * {@code DeadLetterOpsService} 重放走 {@code IdempotentDeliveryService#deliver}
     * （reconsumeTimes 归零），成功 → REPLAYED，仍失败 → 保持 OPEN 并累加 attempts，
     * 丢弃 → DISCARDED；{@code DeadLetterOpsController} 提供
     * {@code GET /api/ops/dead-letters}（payload 截前 2000 字）、
     * {@code POST /api/ops/dead-letters/{id}/replay|discard}（orch:operate），
     * REST 层 mock 测试见 {@code DeadLetterOpsControllerTest}。
     */
    @Nested
    class ReplayAndDiscard {

        private static final String DL_ID = "dl1";
        private static final String OPERATOR_ID = "u1";

        private SysDeadLetterRepository repo;
        private FailingConsumer redelivered;
        private DeadLetterOpsService ops;

        @BeforeEach
        void setUp() {
            repo = mock(SysDeadLetterRepository.class);
            redelivered = new FailingConsumer(0);
            ops = new DeadLetterOpsService(newService(redelivered), repo);
        }

        private SysDeadLetterDO openRow() {
            SysDeadLetterDO row = new SysDeadLetterDO();
            row.setId(DL_ID);
            row.setConsumer("test-consumer");
            row.setTopic("tioka-runtime");
            row.setTag("demo.failed");
            row.setEventId("evt-1");
            row.setPayloadJson(ENVELOPE_JSON);
            row.setError("java.lang.IllegalStateException: boom-3");
            row.setAttempts(MAX_ATTEMPTS);
            row.setStatus(SysDeadLetterRepository.STATUS_OPEN);
            row.setCreateAt(Instant.now());
            return row;
        }

        private SysDeadLetterDO terminalRow(String status) {
            SysDeadLetterDO row = openRow();
            row.setStatus(status);
            row.setHandledBy(OPERATOR_ID);
            row.setHandledAt(Instant.now());
            return row;
        }

        /** 列表：list(status=OPEN) 分页返回死信行；REST 视图 payload 截前 2000 字。 */
        @Test
        void openDeadLettersAreListable() {
            SysDeadLetterDO row = openRow();
            row.setPayloadJson("x".repeat(3000));
            when(repo.list(null, SysDeadLetterRepository.STATUS_OPEN, 0, 20))
                    .thenReturn(List.of(row));
            when(repo.count(null, SysDeadLetterRepository.STATUS_OPEN)).thenReturn(1L);

            DeadLetterOpsService.Page<SysDeadLetterDO> page = ops.list(null,
                    SysDeadLetterRepository.STATUS_OPEN, 1, 20);
            assertEquals(1, page.total());
            assertEquals(1, page.items().size());
            assertEquals(MAX_ATTEMPTS, page.items().get(0).getAttempts());

            Principal operator = new Principal(OPERATOR_ID, "ws-1", PrincipalType.USER,
                    java.util.Set.of(), java.util.Set.of(), "password");
            PrincipalHolder holder = mock(PrincipalHolder.class);
            when(holder.current()).thenReturn(operator);
            DeadLetterOpsService.Page<DeadLetterOpsController.DeadLetterView> view =
                    new DeadLetterOpsController(ops, holder)
                            .list(null, SysDeadLetterRepository.STATUS_OPEN, 1, 20).getData();
            assertEquals(2000, view.items().get(0).payload().length(), "payload 只返回前 2000 字");
        }

        /** 重放：按 payloadJson 重新投递（reconsumeTimes 归零），成功后 status → REPLAYED；重复重放拒绝。 */
        @Test
        void replayRedeliversInOrder() {
            when(repo.findById(DL_ID))
                    .thenReturn(openRow(), terminalRow(SysDeadLetterRepository.STATUS_REPLAYED));
            when(repo.markReplayed(eq(DL_ID), eq(OPERATOR_ID), any(Instant.class))).thenReturn(true);

            SysDeadLetterDO out = ops.replay(DL_ID, OPERATOR_ID);

            assertEquals(1, redelivered.attempts, "重放应恰好重投一次（reconsumeTimes 归零）");
            assertEquals(SysDeadLetterRepository.STATUS_REPLAYED, out.getStatus());
            assertEquals(OPERATOR_ID, out.getHandledBy());
            assertNotNull(out.getHandledAt());
            verify(repo, times(1)).markReplayed(eq(DL_ID), eq(OPERATOR_ID), any(Instant.class));

            // 幂等：已 REPLAYED 的死信不可重复重放
            BizException dup = assertThrows(BizException.class, () -> ops.replay(DL_ID, OPERATOR_ID));
            assertEquals(ErrorCode.CONFLICT, dup.getErrorCode());
            assertEquals(1, redelivered.attempts, "重复重放不得再次投递");
        }

        /** 重放仍失败：保持 OPEN、累加 attempts、回写错误，不置终态。 */
        @Test
        void replayFailureKeepsOpenAndAccumulatesAttempts() {
            FailingConsumer alwaysFails = new FailingConsumer(Integer.MAX_VALUE);
            DeadLetterOpsService failing = new DeadLetterOpsService(newService(alwaysFails), repo);
            when(repo.findById(DL_ID)).thenReturn(openRow());

            BizException e = assertThrows(BizException.class,
                    () -> failing.replay(DL_ID, OPERATOR_ID));
            assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
            assertEquals(1, alwaysFails.attempts);
            verify(repo, times(1)).markRetryFailed(eq(DL_ID), contains("boom"), eq(MAX_ATTEMPTS + 1));
            verify(repo, never()).markReplayed(any(), any(), any(Instant.class));
        }

        /** 丢弃：status → DISCARDED（原因入 error），不再出现在 OPEN 列表；重放被拒绝。 */
        @Test
        void discardClosesDeadLetter() {
            when(repo.findById(DL_ID))
                    .thenReturn(openRow(), terminalRow(SysDeadLetterRepository.STATUS_DISCARDED));
            when(repo.markDiscarded(eq(DL_ID), eq(OPERATOR_ID), any(Instant.class), contains("[discarded:fixed upstream]")))
                    .thenReturn(true);
            when(repo.list(null, SysDeadLetterRepository.STATUS_OPEN, 0, 20)).thenReturn(List.of());
            when(repo.count(null, SysDeadLetterRepository.STATUS_OPEN)).thenReturn(0L);

            SysDeadLetterDO out = ops.discard(DL_ID, OPERATOR_ID, "fixed upstream");

            assertEquals(SysDeadLetterRepository.STATUS_DISCARDED, out.getStatus());
            assertEquals(OPERATOR_ID, out.getHandledBy());
            verify(repo, times(1)).markDiscarded(eq(DL_ID), eq(OPERATOR_ID),
                    any(Instant.class), contains("[discarded:fixed upstream]"));

            DeadLetterOpsService.Page<SysDeadLetterDO> open =
                    ops.list(null, SysDeadLetterRepository.STATUS_OPEN, 1, 20);
            assertEquals(0, open.total(), "丢弃后不再出现在 OPEN 列表");

            BizException e = assertThrows(BizException.class, () -> ops.replay(DL_ID, OPERATOR_ID));
            assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
            assertEquals(0, redelivered.attempts, "已丢弃的死信不得重放");
        }
    }
}

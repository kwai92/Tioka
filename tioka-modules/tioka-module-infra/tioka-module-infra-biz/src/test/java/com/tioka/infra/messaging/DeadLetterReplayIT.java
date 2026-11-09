package com.tioka.infra.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.infra.messaging.infrastructure.SysConsumedRepository;
import com.tioka.infra.messaging.infrastructure.SysDeadLetterRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 *   <li>❌ 缺口：死信可查询 / 重放 / 丢弃入口未实现——{@code SysDeadLetterRepository}
 *       仅有 {@code insert}（16–18 行），无 list/replay/discard，亦无 FE-17
 *       （死信列表、重放、丢弃）依赖的 REST 出口。对应用例以 @Disabled 骨架登记，
 *       见下方 {@link ReplayAndDiscard}。</li>
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
     * ❌ 缺口骨架：死信查询 / 重放 / 丢弃入口未实现（{@code SysDeadLetterRepository}
     * 仅有 insert；无 REST 出口）。实现后重写本组用例：
     *
     * <ol>
     *   <li>可查询：list(status=OPEN) 返回死信行（consumer/topic/eventId/error/attempts）；</li>
     *   <li>重放：replay(id) 按 payloadJson 重新投递（reconsumeTimes 归零），幂等键
     *       已消费过则跳过（sys_consumed），成功后 status → REPLAYED / CLOSED，
     *       handledBy/handledAt 回填；</li>
     *   <li>丢弃：discard(id) status → DISCARDED，不再出现在 OPEN 列表。</li>
     * </ol>
     * 届时 FE-17（M2-00 第 100 行）的前端依赖即满足。
     */
    @Nested
    @Disabled("缺口：死信查询/重放/丢弃入口未实现——SysDeadLetterRepository 仅 insert（16–18 行），无 list/replay/discard 与 REST 出口；实现后重写本组用例")
    class ReplayAndDiscard {

        @Test
        void openDeadLettersAreListable() {
            assertTrue(true, "占位：list(status=OPEN) 断言待实现后补");
        }

        @Test
        void replayRedeliversInOrder() {
            assertTrue(true, "占位：replay 重投 + 状态流转断言待实现后补");
        }

        @Test
        void discardClosesDeadLetter() {
            assertTrue(true, "占位：discard 状态流转断言待实现后补");
        }
    }
}

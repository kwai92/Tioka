package com.tioka.server.messaging;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.infra.messaging.IdempotentDeliveryService;
import com.tioka.infra.messaging.infrastructure.mapper.SysDeadLetterMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ORC-02：消费幂等与死信（34 号验收）。
 */
@SpringBootTest
@Tag("it")
@Import(IdempotentConsumerIT.TestBeans.class)
class IdempotentConsumerIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.consumer.max-attempts", () -> "3");
    }

    @Autowired IdempotentDeliveryService delivery;
    @Autowired CountingConsumer countingConsumer;
    @Autowired FailingConsumer failingConsumer;
    @Autowired SysDeadLetterMapper deadLetterMapper;

    @Test
    void duplicateDeliveryHandledOnce() {
        countingConsumer.reset();
        String eventId = "evtDup" + System.nanoTime();
        EventEnvelope env = sampleEnvelope(eventId, "tioka.orch.run_created.v1", Map.of("runId", "r1"));

        delivery.deliver(countingConsumer.name(), "tioka-orch", env.event(), toJson(env), 0);
        delivery.deliver(countingConsumer.name(), "tioka-orch", env.event(), toJson(env), 0);
        delivery.deliver(countingConsumer.name(), "tioka-orch", env.event(), toJson(env), 0);

        assertEquals(1, countingConsumer.handled(), "同一 eventId 业务只执行 1 次");
    }

    @Test
    void failureGoesToDeadLetter() {
        failingConsumer.reset();
        String eventId = "evtFail" + System.nanoTime();
        EventEnvelope env = sampleEnvelope(eventId, "tioka.orch.run_finished.v1", Map.of("runId", "r2"));

        // 模拟 Broker 三次投递（reconsumeTimes 0/1/2）；前两次抛出以触发重投，末次写死信后确认
        for (int i = 0; i < 3; i++) {
            try {
                delivery.deliver(failingConsumer.name(), "tioka-orch", env.event(), toJson(env), i);
            } catch (RuntimeException ex) {
                assertTrue(i < 2, "末次失败应写死信并确认，不得再抛出: " + ex);
            }
        }

        Long open = deadLetterMapper.selectCount(Wrappers.<SysDeadLetterDO>lambdaQuery()
                .eq(SysDeadLetterDO::getEventId, eventId)
                .eq(SysDeadLetterDO::getStatus, "OPEN"));
        assertEquals(1L, open, "第 3 次失败应写入 sys_dead_letter");
        assertTrue(failingConsumer.attempts() >= 3, "应至少尝试 3 次");
    }

    private static EventEnvelope sampleEnvelope(String eventId, String event, Map<String, Object> payload) {
        return new EventEnvelope(
                eventId,
                event,
                1,
                "ws_idem",
                Instant.now().toString(),
                "idempotent-consumer-it",
                null,
                null,
                null,
                "req_it",
                "orch.run:" + eventId,
                "subj_" + eventId,
                0,
                new EventEnvelope.RunAs("42", "ws_idem", "USER"),
                payload);
    }

    private static String toJson(EventEnvelope env) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(env);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        CountingConsumer countingConsumer() {
            return new CountingConsumer();
        }

        @Bean
        FailingConsumer failingConsumer() {
            return new FailingConsumer();
        }
    }

    static final class CountingConsumer extends IdempotentConsumer<Map<String, Object>> {
        private final AtomicInteger handled = new AtomicInteger();

        @Override
        protected String consumerName() {
            return "idem-it-counting";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            handled.incrementAndGet();
        }

        String name() {
            return consumerName();
        }

        int handled() {
            return handled.get();
        }

        void reset() {
            handled.set(0);
        }
    }

    static final class FailingConsumer extends IdempotentConsumer<Map<String, Object>> {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        protected String consumerName() {
            return "idem-it-failing";
        }

        String name() {
            return consumerName();
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            attempts.incrementAndGet();
            throw new IllegalStateException("forced failure for dead-letter");
        }

        int attempts() {
            return attempts.get();
        }

        void reset() {
            attempts.set(0);
        }
    }
}

package com.tioka.server.messaging;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.OutboxRelay;
import com.tioka.infra.messaging.RelayLock;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 贯通 E2E（publish → sys_outbox → relay → consumer → sys_consumed），in-memory 模式、不引入真实 RocketMQ。
 *
 * <p>覆盖 L3 三点：生产（EventPublisher 落 sys_outbox）、消费（InMemoryMessageTransport 投递到 IdempotentConsumer）、
 * 幂等（sys_consumed 主键 (consumer, event_id) 保证同 eventId 只处理一次）。
 *
 * <p>本测试不改动任何生产代码 / 迁移 / compose；relay 以 {@code relay-enabled=false} 关闭后台循环，
 * 由测试通过 {@link OutboxRelay#drainOnceForTest(String, java.util.function.IntConsumer)} 手动驱动，确保确定性。
 * 需要运行中的 postgres + redis（relay 锁）；无基础设施时由 CI 执行。
 */
@SpringBootTest
@Tag("it")
@Import(OutboxEndToEndIT.TestBeans.class)
class OutboxEndToEndIT {

    private static final String TOPIC = "tioka-e2e-it";
    private static final String EVENT = "tioka.e2e.it_event.v1";
    private static final String CONSUMER = "e2e-it-recorder";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.relay-enabled", () -> "false");
    }

    @Autowired EventPublisher publisher;
    @Autowired PrincipalHolder principals;
    @Autowired TransactionTemplate tx;
    @Autowired RelayLock relayLock;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingConsumer recordingConsumer;

    @Test
    void publishRelayConsumeIdempotentOnce() {
        String token = "e2e-" + UUID.randomUUID();
        assertTrue(relayLock.tryAcquire(token), "测试应能获得 relay 锁以手动驱动 drain");
        try {
            // 清空其它 IT 遗留的未发送 outbox 行（驱动给真实 consumer，避免干扰本用例计数）
            while (relay.drainOnceForTest(token, n -> {}) > 0) {
                // drain leftovers
            }

            recordingConsumer.clear();
            String marker = "e2e-" + System.nanoTime();

            // ① 生产：在业务事务内发布，sys_outbox 落 1 行
            tx.executeWithoutResult(status -> principals.runAs(principal(), () ->
                    publisher.publish(new DomainEvent(EVENT, TOPIC, "key-" + marker,
                            Map.of("marker", marker), 0))));

            // ② 消费：relay 手动 drain 一次 → 投递给 consumer，写 sys_consumed
            int sent1 = relay.drainOnceForTest(token, n -> {});
            // ③ 幂等：同 eventId 已被标记 sent，再次 drain 不得重复投递
            int sent2 = relay.drainOnceForTest(token, n -> {});

            assertEquals(1, sent1, "relay 应恰好投递本用例 1 条");
            assertEquals(0, sent2, "已发送行不得重复投递（同 eventId 只处理一次）");

            // 断言落库：sys_outbox 含本 marker 1 行
            Integer outbox = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_outbox WHERE payload_json LIKE ?",
                    Integer.class, "%" + marker + "%");
            assertEquals(1, outbox, "sys_outbox 应落 1 行（生产点）");

            // 断言消费：consumer 业务 handle 恰好执行 1 次
            assertEquals(1, recordingConsumer.handled(), "同 eventId 业务只执行 1 次（幂等点）");

            // 断言幂等落库：sys_consumed 以 (consumer, event_id) 记 1 行
            Integer consumed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_consumed WHERE consumer = ?",
                    Integer.class, CONSUMER);
            assertEquals(1, consumed, "sys_consumed 应记 1 行（幂等点）");
        } finally {
            relayLock.release(token);
        }
    }

    private static Principal principal() {
        return new Principal("42", "ws_e2e", PrincipalType.USER,
                Set.of("workspace_member"), Set.of("*"), "password");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        RecordingConsumer recordingConsumer() {
            return new RecordingConsumer();
        }
    }

    static final class RecordingConsumer extends com.tioka.framework.messaging.IdempotentConsumer<Map<String, Object>> {
        private final AtomicInteger handled = new AtomicInteger();
        private final List<String> markers = new ArrayList<>();

        @Override
        protected String consumerName() {
            return CONSUMER;
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return TOPIC;
        }

        @Override
        protected String tagExpression() {
            return EVENT;
        }

        @Override
        protected void handle(com.tioka.framework.messaging.EventEnvelope envelope, Map<String, Object> payload) {
            Object m = payload.get("marker");
            if (m != null) {
                markers.add(String.valueOf(m));
            }
            handled.incrementAndGet();
        }

        int handled() {
            return handled.get();
        }

        @SuppressWarnings("unused")
        List<String> markers() {
            return new ArrayList<>(markers);
        }

        void clear() {
            handled.set(0);
            markers.clear();
        }
    }
}

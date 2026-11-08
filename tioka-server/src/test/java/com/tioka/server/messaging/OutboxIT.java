package com.tioka.server.messaging;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.infrastructure.mapper.SysOutboxMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ORC-02：业务事务回滚时 outbox 无行、消费者未收到。
 */
@SpringBootTest
@Tag("it")
@Import(OutboxIT.TestBeans.class)
class OutboxIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired EventPublisher publisher;
    @Autowired PrincipalHolder principals;
    @Autowired TransactionTemplate tx;
    @Autowired SysOutboxMapper outboxMapper;
    @Autowired RecordingConsumer recordingConsumer;

    @Test
    void rollbackDropsEvent() {
        recordingConsumer.clear();
        String marker = "rollback-" + System.nanoTime();

        try {
            tx.executeWithoutResult(status -> {
                principals.runAs(testPrincipal(), () ->
                        publisher.publish(new DomainEvent(
                                "tioka.orch.run_created.v1",
                                "tioka-orch",
                                "run_" + marker,
                                Map.of("runId", "run_" + marker, "marker", marker),
                                0)));
                status.setRollbackOnly();
            });
        } catch (Exception e) {
            fail("回滚场景不应抛业务异常: " + e.getMessage());
        }

        Long count = outboxMapper.selectCount(Wrappers.<SysOutboxDO>lambdaQuery()
                .like(SysOutboxDO::getPayloadJson, marker));
        assertEquals(0L, count, "回滚后 sys_outbox 不得残留");

        awaitQuiet(400);
        assertTrue(recordingConsumer.markers().stream().noneMatch(marker::equals),
                "回滚事件不得投递给消费者: " + recordingConsumer.markers());
    }

    private static Principal testPrincipal() {
        return new Principal("42", "ws_outbox", PrincipalType.USER,
                Set.of("workspace_member"), Set.of("*"), "password");
    }

    private static void awaitQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        RecordingConsumer recordingConsumer() {
            return new RecordingConsumer();
        }
    }

    static final class RecordingConsumer extends com.tioka.framework.messaging.IdempotentConsumer<Map<String, Object>> {
        private final List<String> markers = new CopyOnWriteArrayList<>();

        @Override
        protected String consumerName() {
            return "outbox-it-recorder";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return "tioka-orch";
        }

        @Override
        protected String tagExpression() {
            return "tioka.orch.run_created.v1";
        }

        @Override
        protected void handle(com.tioka.framework.messaging.EventEnvelope envelope, Map<String, Object> payload) {
            Object m = payload.get("marker");
            if (m != null) {
                markers.add(String.valueOf(m));
            }
        }

        List<String> markers() {
            return new ArrayList<>(markers);
        }

        void clear() {
            markers.clear();
        }
    }
}

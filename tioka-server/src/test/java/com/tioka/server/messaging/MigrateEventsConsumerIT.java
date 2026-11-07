package com.tioka.server.messaging;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.infra.messaging.IdempotentDeliveryService;
import com.tioka.runtime.api.approval.ApprovalSubjectHandler;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.kernel.approval.RuntimeApprovalRouterConsumer;
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
 * PK-03 / ORC-02：迁移后的消费者幂等（runtime-approval-router、chat-index）。
 */
@SpringBootTest
@Tag("it")
@Import(MigrateEventsConsumerIT.TestBeans.class)
class MigrateEventsConsumerIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.relay-enabled", () -> "false");
        registry.add("tioka.messaging.consumer.max-attempts", () -> "3");
    }

    @Autowired IdempotentDeliveryService delivery;
    @Autowired RuntimeApprovalRouterConsumer approvalRouter;
    @Autowired CountingApprovalHandler countingHandler;
    @Autowired ChatIndexProbeConsumer chatIndexProbe;

    @Test
    void approvalRouterDuplicateDeliveryHandledOnce() {
        countingHandler.reset();
        String eventId = "aprDup" + System.nanoTime();
        EventEnvelope env = sampleEnvelope(eventId, RuntimeMqTopics.APPROVAL_DECIDED, Map.of(
                "approvalId", "apr_" + eventId,
                "subjectType", "probe_subject",
                "subjectId", "turn_" + eventId,
                "status", "APPROVED",
                "decidedBy", "u1",
                "workspaceId", "ws_mig"));

        String json = toJson(env);
        delivery.deliver(approvalRouter.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.APPROVAL_DECIDED, json, 0);
        delivery.deliver(approvalRouter.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.APPROVAL_DECIDED, json, 0);
        delivery.deliver(approvalRouter.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.APPROVAL_DECIDED, json, 0);

        assertEquals(1, countingHandler.handled(), "approval_decided 同一 eventId 只路由 1 次");
        assertTrue(countingHandler.lastSubjectId().startsWith("turn_"));
    }

    @Test
    void chatIndexDuplicateDeliveryHandledOnce() {
        chatIndexProbe.reset();
        String eventId = "turnDup" + System.nanoTime();
        EventEnvelope env = sampleEnvelope(eventId, RuntimeMqTopics.TURN_FINISHED, Map.of(
                "turnId", "turn_" + eventId,
                "threadId", "thr_" + eventId,
                "status", "COMPLETED",
                "origin", "chat",
                "originRef", "conv_" + eventId));

        String json = toJson(env);
        delivery.deliver(chatIndexProbe.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.TURN_FINISHED, json, 0);
        delivery.deliver(chatIndexProbe.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.TURN_FINISHED, json, 0);
        delivery.deliver(chatIndexProbe.resolvedConsumerName(),
                RuntimeMqTopics.TOPIC, RuntimeMqTopics.TURN_FINISHED, json, 0);

        assertEquals(1, chatIndexProbe.handled(), "turn_finished 同一 eventId 只索引 1 次");
    }

    private static EventEnvelope sampleEnvelope(String eventId, String event, Map<String, Object> payload) {
        return new EventEnvelope(
                eventId,
                event,
                1,
                "ws_mig",
                Instant.now().toString(),
                "migrate-events-it",
                null,
                null,
                null,
                "req_mig",
                "mig:" + eventId,
                "subj_" + eventId,
                0,
                new EventEnvelope.RunAs("42", "ws_mig", "USER"),
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
        CountingApprovalHandler countingApprovalHandler() {
            return new CountingApprovalHandler();
        }

        @Bean
        ChatIndexProbeConsumer chatIndexProbeConsumer() {
            return new ChatIndexProbeConsumer();
        }
    }

    /** 探针主体：不与生产 turn/orch_run/flow_node_run 冲突。 */
    static final class CountingApprovalHandler implements ApprovalSubjectHandler {
        private final AtomicInteger handled = new AtomicInteger();
        private volatile String lastSubjectId = "";

        @Override
        public String subjectType() {
            return "probe_subject";
        }

        @Override
        public void onDecided(ApprovalView approval) {
            handled.incrementAndGet();
            if (approval != null && approval.subjectId() != null) {
                lastSubjectId = approval.subjectId();
            }
        }

        int handled() {
            return handled.get();
        }

        String lastSubjectId() {
            return lastSubjectId;
        }

        void reset() {
            handled.set(0);
            lastSubjectId = "";
        }
    }

    /**
     * 与生产 chat-index 同名订阅表达式的探针；验证 turn_finished 幂等投递路径。
     * （生产 {@code ChatIndexConsumer} 另有业务副作用，此处只计数。）
     */
    static final class ChatIndexProbeConsumer extends IdempotentConsumer<Map<String, Object>> {
        private final AtomicInteger handled = new AtomicInteger();

        @Override
        protected String consumerName() {
            return "chat-index-probe";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return RuntimeMqTopics.TOPIC;
        }

        @Override
        protected String tagExpression() {
            return RuntimeMqTopics.TURN_FINISHED;
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            handled.incrementAndGet();
        }

        int handled() {
            return handled.get();
        }

        void reset() {
            handled.set(0);
        }
    }
}

package com.tioka.orchestration.application.dispatch;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.orchestration.api.mq.OrchestrationMqTopics;
import com.tioka.orchestration.api.watcher.OrchWatcherConsumers;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费者 {@code orch-dispatcher}：处理 {@code tioka.orch.run_created.v1}。
 */
@Component
public class OrchDispatcherConsumer extends IdempotentConsumer<Map<String, Object>> {

    private final OrchDispatchService dispatchService;

    public OrchDispatcherConsumer(OrchDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    protected String consumerName() {
        return OrchWatcherConsumers.DISPATCHER;
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return OrchestrationMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return OrchestrationMqTopics.RUN_CREATED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        Object runId = payload == null ? null : payload.get("runId");
        if (runId == null || String.valueOf(runId).isBlank()) {
            return;
        }
        dispatchService.handleRunCreated(String.valueOf(runId));
    }
}

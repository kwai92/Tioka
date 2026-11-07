package com.tioka.orchestration.watcher;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.orchestration.api.watcher.OrchWatcherConsumers;
import com.tioka.orchestration.api.watcher.TurnFinishedMessage;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import org.springframework.stereotype.Component;

/**
 * 消费者 {@code orch-turn-watcher}：订阅 {@code tioka.runtime.turn_finished.v1} 回写 Run。
 */
@Component
public class OrchTurnWatcher extends IdempotentConsumer<TurnFinishedMessage> {

    private final TurnResultWritebackService writeback;

    public OrchTurnWatcher(TurnResultWritebackService writeback) {
        this.writeback = writeback;
    }

    @Override
    protected String consumerName() {
        return OrchWatcherConsumers.TURN_WATCHER;
    }

    @Override
    protected Class<TurnFinishedMessage> payloadType() {
        return TurnFinishedMessage.class;
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
    protected void handle(EventEnvelope envelope, TurnFinishedMessage payload) {
        writeback.onTurnFinished(payload);
    }
}

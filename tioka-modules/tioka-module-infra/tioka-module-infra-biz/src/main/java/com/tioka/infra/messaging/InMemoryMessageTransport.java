package com.tioka.infra.messaging;

import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * in-memory 模式：中继直接投递给本进程 {@link IdempotentConsumer}（仍走 sys_consumed）。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.messaging", name = "mode", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryMessageTransport implements MessageTransport {

    private final List<IdempotentConsumer<?>> consumers;
    private final IdempotentDeliveryService delivery;

    public InMemoryMessageTransport(List<IdempotentConsumer<?>> consumers,
                                    IdempotentDeliveryService delivery) {
        this.consumers = consumers;
        this.delivery = delivery;
    }

    @Override
    public void send(SysOutboxDO row) {
        for (IdempotentConsumer<?> consumer : consumers) {
            if (!matches(consumer, row)) {
                continue;
            }
            // in-memory：首次投递 reconsumeTimes=0；失败重试由 deliver 内部按 max-attempts 处理
            delivery.deliverAndRetry(consumer, row.getTopic(), row.getTag(), row.getPayloadJson());
        }
    }

    private static boolean matches(IdempotentConsumer<?> consumer, SysOutboxDO row) {
        String topic = consumer.resolvedTopic();
        if (topic != null && !topic.isBlank() && !topic.equals(row.getTopic())) {
            return false;
        }
        String tagExpr = consumer.resolvedTagExpression();
        if (tagExpr == null || tagExpr.isBlank() || "*".equals(tagExpr)) {
            return true;
        }
        return tagExpr.equals(row.getTag());
    }
}

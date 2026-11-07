package com.tioka.infra.messaging;

import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;

import java.util.List;

/**
 * in-memory / 兜底投递：中继直接投递给本进程 {@link IdempotentConsumer}（仍走 sys_consumed）。
 * 由 {@link MessagingConfiguration} 在缺少 RocketMQ Template 时注册。
 */
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

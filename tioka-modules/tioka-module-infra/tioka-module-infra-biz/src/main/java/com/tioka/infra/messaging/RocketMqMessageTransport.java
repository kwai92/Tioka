package com.tioka.infra.messaging;

import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 投递：keys=eventId，sharding key / message group = msg_key。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.messaging", name = "mode", havingValue = "rocketmq")
public class RocketMqMessageTransport implements MessageTransport {

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqMessageTransport(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void send(SysOutboxDO row) {
        String destination = row.getTopic() + ":" + row.getTag();
        var builder = MessageBuilder.withPayload(row.getPayloadJson())
                .setHeader("KEYS", row.getEventId());
        if (row.getMsgKey() != null && !row.getMsgKey().isBlank()) {
            // rocketmq-spring：MESSAGE_KEYS / 顺序消息 sharding key 由 header 传递
            builder.setHeader("rocketmq_KEYS", row.getEventId());
            SendResult result = rocketMQTemplate.syncSendOrderly(
                    destination,
                    builder.build(),
                    row.getMsgKey());
            if (result == null || result.getSendStatus() == null) {
                throw new IllegalStateException("RocketMQ 顺序发送无结果: " + row.getEventId());
            }
            return;
        }
        SendResult result = rocketMQTemplate.syncSend(destination, builder.build());
        if (result == null || result.getSendStatus() == null) {
            throw new IllegalStateException("RocketMQ 发送无结果: " + row.getEventId());
        }
    }
}

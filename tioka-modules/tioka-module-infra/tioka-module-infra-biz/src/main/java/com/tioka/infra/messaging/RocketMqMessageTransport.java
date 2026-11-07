package com.tioka.infra.messaging;

import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;

/**
 * RocketMQ 投递：keys=eventId，sharding key / message group = msg_key。
 * 由 {@link MessagingConfiguration} 在存在 {@link RocketMQTemplate} 且 mode=rocketmq 时注册。
 */
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

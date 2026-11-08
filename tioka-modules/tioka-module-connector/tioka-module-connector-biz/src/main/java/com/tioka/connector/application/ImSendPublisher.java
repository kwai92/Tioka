package com.tioka.connector.application;

import com.tioka.connector.api.mq.ConnectorMqTopics;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 发送入口：登记 {@code tioka.connector.im_send_requested.v1}（ORC-02 / PK-03）。
 * 实际投递由 {@link ImSendConsumer} 消费；不删除旧 im_outbox / 适配器（归 PK-19c）。
 */
@Service
public class ImSendPublisher {
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public ImSendPublisher(EventPublisher events, TransactionTemplate tx) {
        this.events = events;
        this.tx = tx;
    }

    public void requestText(String channel, String bindingId, String conversationId, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", text == null ? "" : text);
        request(channel, bindingId, conversationId, message, "api_text", null, null);
    }

    public void request(String channel,
                        String bindingId,
                        String conversationId,
                        Map<String, Object> message,
                        String purpose,
                        String refType,
                        String refId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingId", bindingId == null || bindingId.isBlank() ? "default" : bindingId);
        payload.put("channel", channel);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("conversationId", conversationId);
        payload.put("target", target);
        payload.put("message", message == null ? Map.of() : message);
        if (purpose != null) {
            payload.put("purpose", purpose);
        }
        if (refType != null) {
            payload.put("refType", refType);
        }
        if (refId != null) {
            payload.put("refId", refId);
        }
        String key = conversationId == null ? channel : channel + ":" + conversationId;
        tx.executeWithoutResult(status -> events.publish(new DomainEvent(
                ConnectorMqTopics.IM_SEND_REQUESTED,
                ConnectorMqTopics.TOPIC,
                key,
                payload,
                0,
                null,
                refId == null ? null : (refType == null ? "im:" : refType + ":") + refId)));
    }
}

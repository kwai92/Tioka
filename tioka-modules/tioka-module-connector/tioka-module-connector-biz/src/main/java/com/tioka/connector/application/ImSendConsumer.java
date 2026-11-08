package com.tioka.connector.application;

import com.tioka.connector.api.mq.ConnectorMqTopics;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.system.api.spi.ImChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消费 {@code tioka.connector.im_send_requested.v1}，委托既有 {@link ImChannelAdapter}（ORC-02 / PK-03）。
 */
@Component
public class ImSendConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(ImSendConsumer.class);

    private final Map<String, ImChannelAdapter> adapters;

    public ImSendConsumer(List<ImChannelAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(a -> a.channelType().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
    }

    @Override
    protected String consumerName() {
        return "im-send";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return ConnectorMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return ConnectorMqTopics.IM_SEND_REQUESTED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String channel = stringOf(payload.get("channel"));
        if (channel == null || channel.isBlank()) {
            log.warn("im_send_requested 缺 channel eventId={}", envelope.eventId());
            return;
        }
        ImChannelAdapter adapter = adapters.get(channel.toLowerCase(Locale.ROOT));
        if (adapter == null) {
            log.warn("无 IM 适配器 channel={} eventId={}", channel, envelope.eventId());
            return;
        }
        String conversationId = conversationIdOf(payload);
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("im_send_requested 缺 conversationId eventId={}", envelope.eventId());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = payload.get("message") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String type = stringOf(message.get("type"));
        if ("card".equalsIgnoreCase(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> card = message.get("card") instanceof Map<?, ?> c
                    ? (Map<String, Object>) c : message;
            adapter.sendCard(conversationId, card);
        } else {
            adapter.sendText(conversationId, stringOf(message.getOrDefault("text", "")));
        }
    }

    /** 同进程即时发送（入口在关 Relay 时调用；适配器侧写 im_outbox 保持兼容）。 */
    public void sendLocally(String channel, String conversationId, String text) {
        ImChannelAdapter adapter = adapters.get(channel.toLowerCase(Locale.ROOT));
        if (adapter != null) {
            adapter.sendText(conversationId, text == null ? "" : text);
        }
    }

    private static String conversationIdOf(Map<String, Object> payload) {
        Object target = payload.get("target");
        if (target instanceof Map<?, ?> m && m.get("conversationId") != null) {
            return String.valueOf(m.get("conversationId"));
        }
        return stringOf(payload.get("conversationId"));
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

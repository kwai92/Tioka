package com.tioka.connector.application;

import com.tioka.connector.api.mq.ConnectorMqTopics;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.system.api.spi.ImChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消费 {@code tioka.connector.im_send_requested.v1}，委托既有 {@link ImChannelAdapter}（ORC-02 / PK-03）。
 * DAT-02：发送前经 {@link SinkGuard}(IM, conversationId) 去向许可——
 * DENY 丢弃并记审计；REDACTED 用脱敏文本发送。标签取 payload.dataLabel（未带 = INTERNAL 缺省，
 * 生产链路标签位由 NTF/G3 批次接入，登记偏差于批次文档）。
 */
@Component
public class ImSendConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(ImSendConsumer.class);

    private final Map<String, ImChannelAdapter> adapters;
    private final ObjectProvider<SinkGuard> sinkGuard;

    public ImSendConsumer(List<ImChannelAdapter> adapterList, ObjectProvider<SinkGuard> sinkGuard) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(a -> a.channelType().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
        this.sinkGuard = sinkGuard;
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
            String text = stringOf(message.getOrDefault("text", ""));
            adapter.sendText(conversationId, permitted(conversationId, text, payload));
        }
    }

    /** DAT-02：IM 去向许可前置。返回实际发送文本；DENY 返回 null（丢弃）。 */
    private String permitted(String conversationId, String text, Map<String, Object> payload) {
        SinkGuard guard = sinkGuard.getIfAvailable();
        if (guard == null) {
            return text;
        }
        DataLabel label = DataLabel.INTERNAL;
        Object raw = payload.get("dataLabel");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                label = DataLabel.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 非法标签按缺省 INTERNAL 处理
            }
        }
        SinkCheckResult r = guard.check(SinkKind.IM, conversationId, label, text);
        switch (r.verdict()) {
            case PASS:
                return text;
            case REDACTED:
                log.info("im send REDACTED conversation={}", conversationId);
                return r.redactedPayload() instanceof String s ? s : String.valueOf(r.redactedPayload());
            case REQUIRE_APPROVAL:
            case DENY:
            default:
                log.warn("im send denied by SinkGuard conversation={} verdict={} reason={}",
                        conversationId, r.verdict(), r.reason());
                return null;
        }
    }

    /** 同进程即时发送（入口在关 Relay 时调用；适配器侧写 im_outbox 保持兼容）。 */
    public void sendLocally(String channel, String conversationId, String text) {
        ImChannelAdapter adapter = adapters.get(channel.toLowerCase(Locale.ROOT));
        if (adapter != null) {
            String permitted = permitted(conversationId, text == null ? "" : text, Map.of());
            if (permitted != null) {
                adapter.sendText(conversationId, permitted);
            }
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

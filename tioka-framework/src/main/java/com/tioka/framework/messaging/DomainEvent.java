package com.tioka.framework.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 待发布的跨域事件；信封其余字段（eventId、tenantId、runAs、traceId 等）由 {@link EventPublisher} 补齐。
 *
 * @param event      事件名，{@code tioka.{domain}.{event}.v{n}}（ADR-029 第 15 条）
 * @param topic      RocketMQ 主题，每个领域一个，如 {@code tioka-orch}
 * @param subjectKey 顺序与分组键，可空
 * @param payload    事件专属内容，结构见 {@code docs/contracts/events/{event}.json}
 * @param hops       派生深度，可空表示 0
 */
public record DomainEvent(String event, String topic, String subjectKey, Map<String, Object> payload, Integer hops) {

    private static final Pattern NAME = Pattern.compile("^tioka\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_.]*\\.v[0-9]+$");

    public DomainEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(topic, "topic");
        if (!NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("事件名不符合 tioka.{domain}.{event}.v{n}：" + event);
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}

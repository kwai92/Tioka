package com.tioka.governance.application.timeline;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * 消费 {@code tioka.obs.timeline_source.v1}（OBS-08 / ADR-075 §2）：各域发布的来源事件投影为
 * {@code timeline_entry}。消费注册按既有 {@code RocketMqConsumerBootstrap} 惯例。
 */
public class TimelineProjectionConsumer extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(TimelineProjectionConsumer.class);

    private final TimelineProjector projector;

    public TimelineProjectionConsumer(TimelineProjector projector) {
        this.projector = projector;
    }

    @Override
    protected String consumerName() {
        return "governance-timeline";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return GovernanceMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return GovernanceMqTopics.TIMELINE_SOURCE;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        TimelineSourceEvent event = toSourceEvent(envelope, payload);
        if (event != null) {
            log.debug("timeline projection consumed eventId={}", event.sourceEventId());
            projector.project(event);
        }
    }

    /** IT/local：同进程即时投影（handler 幂等）。 */
    public void routeLocally(TimelineSourceEvent event) {
        projector.project(event);
    }

    /** 信封 + payload → 来源事件；关联键优先取 payload，缺失回落到信封（ADR-073）。 */
    static TimelineSourceEvent toSourceEvent(EventEnvelope envelope, Map<String, Object> payload) {
        if (envelope == null) {
            return null;
        }
        Map<String, Object> attrs = payload == null ? Map.of() : payload;
        return new TimelineSourceEvent(
                envelope.eventId(),
                envelope.event(),
                firstNonBlank(str(attrs.get("requestId")), envelope.requestId()),
                str(attrs.get("parentRequestId")),
                firstNonBlank(str(attrs.get("subjectType")), subjectType(envelope.subject())),
                firstNonBlank(str(attrs.get("subjectId")), subjectId(envelope.subject())),
                firstNonBlank(str(attrs.get("workspaceId")), envelope.workspaceId()),
                instant(firstNonBlank(str(attrs.get("occurredAt")), envelope.occurredAt())),
                attrs);
    }

    private static String str(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? null : s;
    }

    /** 主体引用形如 {@code type:id}（ADR-073 / ADR-075 §6）；无冒号则视为无类型主体。 */
    private static String subjectType(String subject) {
        String s = str(subject);
        if (s == null) {
            return null;
        }
        int idx = s.indexOf(':');
        return idx > 0 ? s.substring(0, idx) : null;
    }

    private static String subjectId(String subject) {
        String s = str(subject);
        if (s == null) {
            return null;
        }
        int idx = s.indexOf(':');
        return idx > 0 ? s.substring(idx + 1) : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ignored) {
            return Instant.now();
        }
    }
}

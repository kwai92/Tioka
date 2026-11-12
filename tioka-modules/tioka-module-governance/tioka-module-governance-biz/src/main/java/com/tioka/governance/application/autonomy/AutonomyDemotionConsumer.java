package com.tioka.governance.application.autonomy;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * TRU-10 降级事件消费者（M3-05 §8）。订阅 {@code tioka-governance}、tag {@code *}
 * （in-memory transport 按 tag 全等匹配，不支持 {@code ||}；与 IdentityCharterConsumer 同口径），
 * 按信封事件名分流三个入口；业务幂等由 V226 台账 + 等级只读判定共同保证。
 */
public class AutonomyDemotionConsumer extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(AutonomyDemotionConsumer.class);

    private final AutonomyDemotionService service;

    public AutonomyDemotionConsumer(AutonomyDemotionService service) {
        this.service = service;
    }

    @Override
    protected String consumerName() {
        return "governance-autonomy-demotion";
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
        return "*";
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null || envelope == null) {
            return;
        }
        String event = envelope.event();
        String eventId = envelope.eventId();
        String agentId = text(payload.get("agentId"));
        String workspaceId = text(payload.get("workspaceId"));
        switch (event == null ? "" : event) {
            case GovernanceMqTopics.TRUST_ACTION_UNDONE -> {
                String actionClass = text(payload.get("actionCategory"));
                String scopeId = text(payload.get("undoScopeId"));
                String fallback = text(payload.get("actionId"));
                String key = "undo:" + (blank(scopeId) ? fallback : scopeId) + ":" + actionClass;
                service.demoteOne(agentId, workspaceId, actionClass, trimKey(key), eventId,
                        "action undone (" + fallback + ")");
            }
            case GovernanceMqTopics.TRUST_COMPENSATION_FAILED -> {
                String actionId = text(payload.get("actionId"));
                String key = "compfail:" + agentId + ":" + actionId;
                service.demoteAll(agentId, workspaceId, trimKey(key), eventId,
                        "compensation failed (" + actionId + ")");
            }
            case GovernanceMqTopics.TRUST_UNDO_CIRCUIT_OPEN -> {
                String windowStart = text(payload.get("windowStart"));
                String key = "circuit:" + agentId + ":" + windowStart;
                service.demoteAll(agentId, workspaceId, trimKey(key), eventId,
                        "undo circuit opened (" + text(payload.get("reason")) + ")");
            }
            default -> log.debug("autonomy demotion consumer 忽略事件 {}", event);
        }
    }

    /** demotion_key VARCHAR(160)：超长时保留前缀并追加哈希，避免 DB 报错。 */
    private static String trimKey(String key) {
        if (key.length() <= 150) {
            return key;
        }
        return key.substring(0, 120) + ":" + Integer.toHexString(key.hashCode());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

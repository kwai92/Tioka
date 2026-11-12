package com.tioka.governance.application.charter;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.system.api.mq.SystemMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * TRU-03（M3-05 §4 / M1-01 ID-BR）：消费身份生命周期事件，驱动授权书暂停。
 *
 * <ul>
 *   <li>{@code tioka.identity.user_disabled.v1} → {@code suspendByOwnerDisable(userId)}</li>
 *   <li>{@code tioka.identity.member_removed.v1} → {@code suspendByMemberRemove(ws, userId)}</li>
 * </ul>
 *
 * <p>订阅 topic {@code tioka-system}、tag {@code *}（in-memory transport 按 tag 全等匹配，
 * 不支持 {@code ||} 表达式），按信封事件名分流。消费幂等由状态机 CAS 保证（重复投递只处理一次）。
 * 由 CharterAutoConfiguration 装配（受 {@code tioka.trust.charter.enabled} 约束）。
 */
public class IdentityCharterConsumer extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(IdentityCharterConsumer.class);

    private final CharterLifecycleService lifecycle;

    public IdentityCharterConsumer(CharterLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    protected String consumerName() {
        return "governance-charter-lifecycle";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return SystemMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        // in-memory transport 按 tag 全等匹配（不支持 ||）；topic 内事件量小，收全量按事件名分流
        return "*";
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String event = envelope.event();
        String userId = text(payload.get("userId"));
        if (SystemMqTopics.USER_DISABLED.equals(event)) {
            lifecycle.suspendByOwnerDisable(userId);
        } else if (SystemMqTopics.MEMBER_REMOVED.equals(event)) {
            lifecycle.suspendByMemberRemove(text(payload.get("workspaceId")), userId);
        } else {
            log.debug("charter lifecycle consumer 忽略事件 {} eventId={}", event, envelope.eventId());
        }
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

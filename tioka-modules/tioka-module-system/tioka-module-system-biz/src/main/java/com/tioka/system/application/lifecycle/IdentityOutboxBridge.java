package com.tioka.system.application.lifecycle;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.system.api.mq.SystemMqTopics;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ID-BR / PK-08：身份生命周期 Spring 事件旁路，经 outbox 发布契约事件（与业务写入同事务）。
 */
@Component
public class IdentityOutboxBridge {
    private final EventPublisher publisher;

    public IdentityOutboxBridge(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishUserDisabled(String userId, String operatorId, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("operatorId", operatorId == null ? "" : operatorId);
        payload.put("at", (at == null ? Instant.now() : at).toString());
        publisher.publish(new DomainEvent(
                SystemMqTopics.USER_DISABLED,
                SystemMqTopics.TOPIC,
                userId,
                payload,
                0,
                null,
                "user:" + userId));
    }

    public void publishMemberRemoved(String workspaceId, String userId, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspaceId);
        payload.put("userId", userId);
        payload.put("source", source == null ? "" : source);
        publisher.publish(new DomainEvent(
                SystemMqTopics.MEMBER_REMOVED,
                SystemMqTopics.TOPIC,
                workspaceId + ":" + userId,
                payload,
                0,
                null,
                "workspace_member:" + workspaceId + ":" + userId));
    }
}

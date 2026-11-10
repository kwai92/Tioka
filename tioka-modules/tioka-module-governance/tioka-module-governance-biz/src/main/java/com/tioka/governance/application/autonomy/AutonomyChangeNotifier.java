package com.tioka.governance.application.autonomy;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 等级变更事件出口：setLevel / markIncident 后发 {@code tioka.trust.autonomy_changed.v1}
 * （契约见 {@code docs/contracts/events/tioka.trust.autonomy_changed.v1.json}）。
 * 经 outbox 发布（{@link EventPublisher}，须在业务事务内）；发布失败不阻断调级写库。
 * 消费侧（档案页缓存刷新）M3 无需求则不注册，登记。
 */
public class AutonomyChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(AutonomyChangeNotifier.class);

    private final EventPublisher publisher;
    /** 事件抽象（测试用 mock 直接断言；生产即 publisher）。 */
    private final Consumer<Map<String, Object>> sink;

    public AutonomyChangeNotifier(EventPublisher publisher) {
        this.publisher = publisher;
        this.sink = payload -> publisher.publish(new DomainEvent(
                GovernanceMqTopics.TRUST_AUTONOMY_CHANGED,
                GovernanceMqTopics.TOPIC,
                String.valueOf(payload.get("workspaceId")),
                payload,
                0,
                null,
                "trust_autonomy:" + payload.get("agentId")));
    }

    /** 测试 / 离线装配：只挂 sink 不经 outbox。 */
    public AutonomyChangeNotifier(Consumer<Map<String, Object>> sink, boolean testOnly) {
        this.publisher = null;
        this.sink = sink;
    }

    public void notifyChanged(String workspaceId, String agentId, String actionClass,
                             String oldLevel, String newLevel, String reason) {
        Map<String, Object> payload = Map.of(
                "workspaceId", workspaceId == null ? "" : workspaceId,
                "agentId", agentId == null ? "" : agentId,
                "actionCategory", actionClass == null ? "" : actionClass,
                "oldLevel", oldLevel == null ? "" : oldLevel,
                "newLevel", newLevel == null ? "" : newLevel,
                "reason", reason == null ? "" : reason);
        try {
            sink.accept(payload);
        } catch (RuntimeException e) {
            log.warn("autonomy_changed 事件发布失败: {}", e.toString());
        }
    }
}

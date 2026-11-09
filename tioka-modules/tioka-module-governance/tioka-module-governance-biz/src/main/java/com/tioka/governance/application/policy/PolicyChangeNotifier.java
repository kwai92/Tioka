package com.tioka.governance.application.policy;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 策略变更事件出口（M3-05 §1.5）：策略增删 / 模式切换 / 授权书编译后发
 * {@code tioka.gov.policy_changed.v1}（带空间与新版本号），各实例收到后失效该空间缓存。
 * 经 outbox 发布（{@link EventPublisher}，须在业务事务内）；事件抽象可替换（测试断言用）。
 */
public class PolicyChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(PolicyChangeNotifier.class);

    private final EventPublisher publisher;
    /** 事件抽象（测试用 mock 直接断言；生产即 publisher）。 */
    private final Consumer<Map<String, Object>> sink;

    public PolicyChangeNotifier(EventPublisher publisher) {
        this.publisher = publisher;
        this.sink = payload -> publisher.publish(new DomainEvent(
                GovernanceMqTopics.POLICY_CHANGED,
                GovernanceMqTopics.TOPIC,
                String.valueOf(payload.get("workspaceId")),
                payload,
                0,
                null,
                "policy:" + payload.get("workspaceId")));
    }

    /** 测试 / 离线装配：只挂 sink 不经 outbox。 */
    public PolicyChangeNotifier(Consumer<Map<String, Object>> sink, boolean testOnly) {
        this.publisher = null;
        this.sink = sink;
    }

    public void notifyChanged(String workspaceId, int policyVersion, String source, String mode) {
        Map<String, Object> payload = Map.of(
                "workspaceId", workspaceId == null ? "" : workspaceId,
                "policyVersion", policyVersion,
                "source", source == null ? "" : source,
                "mode", mode == null ? "" : mode);
        try {
            sink.accept(payload);
        } catch (RuntimeException e) {
            // outbox 发布失败不阻断策略写入：60 秒缓存 TTL 兜底
            log.warn("policy_changed 事件发布失败（缓存 TTL 兜底）: {}", e.toString());
        }
    }
}

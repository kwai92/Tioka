package com.tioka.governance.application.policy;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 消费 {@code tioka.gov.policy_changed.v1}：失效该空间策略缓存（M3-05 §1.5 跨实例刷新；
 * 缓存另有 60 秒 TTL 兜底）。消费注册按既有 RocketMqConsumerBootstrap 惯例。
 */
public class PolicyChangedConsumer extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(PolicyChangedConsumer.class);

    private final PolicyCache cache;

    public PolicyChangedConsumer(PolicyCache cache) {
        this.cache = cache;
    }

    @Override
    protected String consumerName() {
        return "governance-policy-cache";
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
        return GovernanceMqTopics.POLICY_CHANGED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        Object ws = payload.get("workspaceId");
        if (ws != null) {
            cache.invalidate(String.valueOf(ws));
            log.debug("policy cache invalidated ws={} eventId={}", ws, envelope.eventId());
        }
    }

    /** IT/local：同进程即时失效（handler 幂等）。 */
    public void routeLocally(String workspaceId) {
        cache.invalidate(workspaceId);
    }
}

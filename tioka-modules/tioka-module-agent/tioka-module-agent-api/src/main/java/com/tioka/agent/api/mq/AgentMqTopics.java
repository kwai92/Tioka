package com.tioka.agent.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class AgentMqTopics {

    public static final String TOPIC = "tioka-agent";

    public static final String TAG_AGENT_PUBLISHED = "agent.published";
    public static final String TAG_AGENT_ENABLED = "agent.enabled";
    public static final String TAG_BINDING_CHANGED = "binding.changed";

    private AgentMqTopics() {
    }
}

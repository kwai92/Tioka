package com.tioka.agent.api.mq;

/**
 * Agent / MAS MQ Topic/Tag 与跨域事件名（禁止在 biz 内硬编码字符串）。
 */
public final class AgentMqTopics {

    public static final String TOPIC = "tioka-agent";

    public static final String TAG_AGENT_PUBLISHED = "agent.published";
    public static final String TAG_AGENT_ENABLED = "agent.enabled";
    public static final String TAG_BINDING_CHANGED = "binding.changed";

    /** MAS-01 委派授予签发。 */
    public static final String GRANT_ISSUED = "tioka.mas.grant_issued.v1";
    /** MAS-01/03 授予回收。 */
    public static final String GRANT_REVOKED = "tioka.mas.grant_revoked.v1";

    private AgentMqTopics() {
    }
}

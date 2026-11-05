package com.tioka.mcp.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class McpMqTopics {

    public static final String TOPIC = "tioka-mcp";

    public static final String TAG_SERVER_REGISTERED = "server.registered";
    public static final String TAG_TOOLS_REFRESHED = "tools.refreshed";
    public static final String TAG_GRANT_CHANGED = "grant.changed";
    public static final String TAG_HEALTH_FAILED = "health.failed";

    private McpMqTopics() {
    }
}

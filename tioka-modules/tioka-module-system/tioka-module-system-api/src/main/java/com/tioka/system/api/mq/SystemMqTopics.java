package com.tioka.system.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class SystemMqTopics {

    public static final String TOPIC = "tioka-system";

    public static final String TAG_USER_CREATED = "user.created";
    public static final String TAG_ROLE_CHANGED = "role.changed";
    public static final String TAG_AUDIT_EMITTED = "audit.emitted";

    private SystemMqTopics() {
    }
}

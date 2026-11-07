package com.tioka.system.api.mq;

/**
 * MQ Topic / 事件名常量（禁止在 biz 内硬编码字符串）。
 * 身份生命周期事件域名为 {@code identity}，由 system 模块经 outbox 桥接（M1-01 F7 / ID-BR）。
 */
public final class SystemMqTopics {

    public static final String TOPIC = "tioka-system";

    public static final String USER_DISABLED = "tioka.identity.user_disabled.v1";
    public static final String MEMBER_REMOVED = "tioka.identity.member_removed.v1";

    private SystemMqTopics() {
    }
}

package com.tioka.governance.api.mq;

/**
 * Governance MQ Topic/Tag（禁止在 biz 内硬编码字符串）。
 * <p>事件契约：{@code docs/contracts/events/cfg-version-published-v1.json}
 * （M3-00：{@code cfg.version_published.v1}；ADR-041 广播名 {@code tioka.config.published.v1} 语义等同）。
 */
public final class GovernanceMqTopics {

    public static final String TOPIC = "tioka-governance";

    /** 配置版本发布或回滚后广播（投影刷新）。 */
    public static final String TAG_VERSION_PUBLISHED = "cfg.version_published.v1";

    private GovernanceMqTopics() {
    }
}

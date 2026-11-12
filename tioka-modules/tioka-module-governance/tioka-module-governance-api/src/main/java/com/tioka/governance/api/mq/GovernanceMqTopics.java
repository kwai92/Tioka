package com.tioka.governance.api.mq;

/**
 * Governance MQ Topic/Tag 与跨域事件名（禁止在 biz 内硬编码字符串）。
 * <p>遗留 tag：{@code cfg.version_published.v1}（见 {@code cfg-version-published-v1.json}）。
 * M3 权威信封事件名见 {@code docs/contracts/events/tioka.*.v1.json}（P3-00a）。
 */
public final class GovernanceMqTopics {

    public static final String TOPIC = "tioka-governance";

    /** 配置版本发布或回滚后广播（投影刷新；遗留 tag，语义同 {@link #CONFIG_PUBLISHED}）。 */
    public static final String TAG_VERSION_PUBLISHED = "cfg.version_published.v1";

    public static final String CONFIG_PUBLISHED = "tioka.config.published.v1";
    public static final String POLICY_CHANGED = "tioka.gov.policy_changed.v1";
    public static final String QUOTA_THRESHOLD = "tioka.quota.threshold.v1";
    public static final String TRUST_ACTION_UNDONE = "tioka.trust.action_undone.v1";
    public static final String TRUST_UNDO_RECORDED = "tioka.trust.undo_recorded.v1";
    public static final String TRUST_COMPENSATE = "tioka.trust.compensate.v1";
    public static final String TRUST_COMPENSATION_FAILED = "tioka.trust.compensation_failed.v1";
    /** TRU-08 撤销熔断开启（C-21 / M3-05 §6）；由 TRU-10 消费端全类别降 L1。 */
    public static final String TRUST_UNDO_CIRCUIT_OPEN = "tioka.trust.undo_circuit_open.v1";
    public static final String TRUST_AUTONOMY_CHANGED = "tioka.trust.autonomy_changed.v1";
    public static final String RUN_MANIFEST_WRITTEN = "tioka.obs.run_manifest_written.v1";

    private GovernanceMqTopics() {
    }
}

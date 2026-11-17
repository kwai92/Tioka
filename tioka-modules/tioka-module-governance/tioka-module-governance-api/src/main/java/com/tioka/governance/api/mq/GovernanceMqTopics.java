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

    /** OBS-08 统一时间线来源事件（ADR-075 §2）：各域发布于本 topic，投影消费者写入 {@code timeline_entry}。 */
    public static final String TIMELINE_SOURCE = "tioka.obs.timeline_source.v1";
    public static final String QUOTA_THRESHOLD = "tioka.quota.threshold.v1";
    public static final String TRUST_ACTION_UNDONE = "tioka.trust.action_undone.v1";
    public static final String TRUST_UNDO_RECORDED = "tioka.trust.undo_recorded.v1";
    public static final String TRUST_COMPENSATE = "tioka.trust.compensate.v1";
    public static final String TRUST_COMPENSATION_FAILED = "tioka.trust.compensation_failed.v1";
    /** TRU-08 撤销熔断开启（C-21 / M3-05 §6）；由 TRU-10 消费端全类别降 L1。 */
    public static final String TRUST_UNDO_CIRCUIT_OPEN = "tioka.trust.undo_circuit_open.v1";
    public static final String TRUST_AUTONOMY_CHANGED = "tioka.trust.autonomy_changed.v1";
    public static final String RUN_MANIFEST_WRITTEN = "tioka.obs.run_manifest_written.v1";
    /** OBS-07 → OBS-08：外部交互台账落库的来源事件名（时间线 INBOUND/OUTBOUND/NOTIFY/MODEL/TOOL 条目）。 */
    public static final String EXT_INTERACTION_RECORDED = "tioka.obs.ext_interaction_recorded.v1";
    /** OBS-08：Turn 内按步合并的模型/工具来源事件名（时间线 MODEL/TOOL 条目）。 */
    public static final String RUNTIME_ITEM_RECORDED = "tioka.obs.runtime_item_recorded.v1";
    /** OBS-08：Turn 内模型用量落库的来源事件名（时间线 MODEL 条目）。 */
    public static final String MODEL_USAGE_RECORDED = "tioka.obs.model_usage_recorded.v1";
    /** OBS-08：审批单落库的来源事件名（时间线 APPROVAL 条目，ADR-075 §2）。 */
    public static final String APPROVAL_RECORDED = "tioka.obs.approval_recorded.v1";
    /** OBS-08：编排规则求值落库的来源事件名（时间线 DECISION 条目，ADR-075 §2/§6）。 */
    public static final String DECISION_RECORDED = "tioka.obs.decision_recorded.v1";
    /** OBS-08：用户反馈落库的来源事件名（时间线 FEEDBACK 条目，ADR-075 §2「反馈项」）。 */
    public static final String FEEDBACK_RECORDED = "tioka.obs.feedback_recorded.v1";
    /** OBS-08：变更动作落库的来源事件名（时间线 CHANGE 条目，ADR-075 §2「变更单、提交、撤销」）。 */
    public static final String CHANGE_RECORDED = "tioka.obs.change_recorded.v1";
    /** OBS-08：配置变更落库的来源事件名（时间线 CONFIG 背景条目，ADR-075 §2）。 */
    public static final String CONFIG_RECORDED = "tioka.obs.config_recorded.v1";
    /** OBS-08：安全类审计事件的来源事件名（时间线 SECURITY 条目，ADR-075 §2）。 */
    public static final String SECURITY_RECORDED = "tioka.obs.security_recorded.v1";

    private GovernanceMqTopics() {
    }
}

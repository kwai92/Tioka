package com.tioka.runtime.api.pipeline;

/**
 * 工具管线固定序号表（M3-05 第 0 节；P3-00b / K1b）。
 * 调整序号须持有 K1b；各业务包只新增步骤类，不改本表。
 */
public final class ToolPipelineOrders {

    private ToolPipelineOrders() {}

    /** 委派授予（MAS-01）。 */
    public static final double DELEGATION_GRANT = 2.5;

    /** 授权书检查（TRU-01〜03）。 */
    public static final double CHARTER_CHECK = 7.0;

    /** 预演 / 变更单（TRU-05，M2 已有）。 */
    public static final double PREPARE_CHANGESET = 8.0;

    /** Cedar 策略求值（GOV-07、TRU-02）。 */
    public static final double POLICY_EVAL = 9.0;

    /** 自治等级判定（TRU-09）。 */
    public static final double AUTONOMY_GATE = 10.0;

    /** 爆炸半径（TRU-08）。 */
    public static final double BLAST_RADIUS = 11.0;

    /** 审计（GOV-07）。 */
    public static final double AUDIT = 12.0;

    /** 去向许可（DAT-02）；序号预留，实现包登记。 */
    public static final double EGRESS_PERMIT = 12.5;

    /** 护栏（GRD-00 / M4）；序号预留。 */
    public static final double GUARDRAIL = 13.0;

    /** 台账（M4）；序号预留。 */
    public static final double LEDGER = 14.0;
}

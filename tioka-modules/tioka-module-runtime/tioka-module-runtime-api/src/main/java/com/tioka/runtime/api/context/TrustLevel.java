package com.tioka.runtime.api.context;

/**
 * 内容信任分级（ADR-065 第 2.1 节；GRD-00）。
 *
 * <p>进入上下文的每一段都带信任级别：{@code SYSTEM}（平台安全指令、Agent 指令、Skill、
 * 输出契约说明）、{@code PRINCIPAL}（发起本 Turn 的用户本人输入）、{@code INTERNAL}
 * （本部署内经审核的数据）、{@code EXTERNAL}（上传文档、OCR 文字、未审核知识库、
 * MCP / 网页结果、他人消息、A2A 回复、子 Agent 自由文本产物）。
 *
 * <p>只有 {@code EXTERNAL} 需要每 Turn 随机的边界包裹与来源标注（第 2.2 节），
 * 也只有它会把本 Turn 置为污点（第 3 节）。
 */
public enum TrustLevel {
    /** 平台安全指令、Agent 指令、Skill（已发布）、输出契约说明。 */
    SYSTEM,
    /** 发起本 Turn 的用户本人输入、本人确认过的计划。 */
    PRINCIPAL,
    /** 本部署内经审核的数据（带来源标注，不加边界）。 */
    INTERNAL,
    /** 上传文档、OCR、未审核知识库、MCP / 网页结果、他人消息、A2A 回复、子 Agent 自由文本。 */
    EXTERNAL;

    /** ADR-065 §2.1：是否需要边界包裹 + 来源标注（仅 EXTERNAL）。 */
    public boolean requiresBoundary() {
        return this == EXTERNAL;
    }
}

package com.tioka.ontology.api.schema;

/**
 * 结构化产出失败原因码（ADR-066 §1.2 / §5 / §6 / §7）。
 *
 * <p>原因码是「失败」这一结局的载荷，而不是异常。任何一步失败都以原因码落盘，
 * 供修复（§6）、降级（§7）与审计消费。
 */
public enum ReasonCode {

    // ---- 第 1 步：完成状态 ----
    TRUNCATED(Stage.COMPLETION),
    REFUSED(Stage.COMPLETION),
    EMPTY(Stage.COMPLETION),
    CONTENT_FILTERED(Stage.COMPLETION),

    // ---- 第 2 步：提取 ----
    NO_JSON(Stage.EXTRACT),
    MULTIPLE_JSON(Stage.EXTRACT),

    // ---- 第 3 步：严格解析 ----
    SYNTAX(Stage.PARSE),

    // ---- 第 5 步：Schema 校验 ----
    SCHEMA(Stage.SCHEMA),

    // ---- 第 6 步：语义规则 ----
    RULE(Stage.RULE),
    /** CEL 求值超时（ADR-066 §5.1，50 ms）。 */
    RULE_TIMEOUT(Stage.RULE),

    // ---- 第 7 步：证据与引用 ----
    EVIDENCE(Stage.EVIDENCE),
    QUOTE_MISMATCH(Stage.EVIDENCE),
    PATH_NOT_FOUND(Stage.EVIDENCE),
    REF_NOT_FOUND(Stage.EVIDENCE),
    UNKNOWN_PLACEHOLDER(Stage.EVIDENCE),

    // ---- 第 8 步：领域钩子 ----
    HOOK(Stage.HOOK),

    // ---- 修复 / 降级 ----
    BUDGET(Stage.FALLBACK),
    NO_ELIGIBLE_MODEL(Stage.FALLBACK),
    /** 人工补录超时（ADR-066 §7.4，缺省 3 个工作日）。 */
    HUMAN_TIMEOUT(Stage.FALLBACK),

    // ---- 契约与兜底 ----
    /** 无可用输出契约（沿用 OutputStage 旧口径）。 */
    NO_CONTRACT(Stage.INTERNAL),
    /** Turn 结束前没有任何有效提交（TOOL 模式）。 */
    NO_OUTPUT(Stage.INTERNAL),
    /** 流水线内部异常，兜底结局（ADR-066 §1.3，不向调用方传播）。 */
    INTERNAL(Stage.INTERNAL);

    /** 原因码所属阶段。 */
    public enum Stage {
        COMPLETION,
        EXTRACT,
        PARSE,
        SCHEMA,
        RULE,
        EVIDENCE,
        HOOK,
        FALLBACK,
        INTERNAL
    }

    private final Stage stage;

    ReasonCode(Stage stage) {
        this.stage = stage;
    }

    public Stage stage() {
        return stage;
    }

    /** 宽松解析（大小写不敏感），识别不了返回 {@link #INTERNAL}。 */
    public static ReasonCode of(String name) {
        if (name == null || name.isBlank()) {
            return INTERNAL;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return INTERNAL;
        }
    }
}

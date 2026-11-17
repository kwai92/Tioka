package com.tioka.model.application.conformance;

/**
 * SCH-10（ADR-066 §10.2）：一致性测试集的单条探针。
 *
 * <p>「模型接入测试」对每个模型版本逐生成方式运行本测试集，测出 {@code modes}、{@code keywords}、
 * {@code limits}、{@code thinkingCompat}、{@code multimodalDowngrade} 的实际值并写回能力记录
 * （{@link com.tioka.model.api.profile.StructuredOutputCapability}）。
 *
 * <p>探针只描述「发什么、要求什么」；怎么调用模型由 {@link ConformanceProbeExecutor} 决定
 * （真实模型调用归 CI 闸门，见 ADR-066 §10.4「模型启用结构化能力」）。
 *
 * @param id                 探针标识（稳定，用于判定聚合）
 * @param category           覆盖维度
 * @param level              量级（{@code DEPTH}/{@code SIZE}/{@code ENUM} 用；其余为 0）
 * @param strictKeyword      严格档关键字（{@code KEYWORD} 用；其余为空）
 * @param requiresMultimodal 需多模态输入（有图片）
 * @param requiresThinking   需开启思考模式
 * @param prompt             提示词
 * @param schemaJson         权威 Schema（严格档子集）
 */
public record ConformanceProbe(
        String id,
        Category category,
        int level,
        String strictKeyword,
        boolean requiresMultimodal,
        boolean requiresThinking,
        String prompt,
        String schemaJson) {

    /** 覆盖维度（ADR-066 §10.2「严格档全部关键字组合、深度与规模上限、枚举、可空、引用、中文长文本、多模态输入、思考模式开关」）。 */
    public enum Category {
        /** 严格档基线：最简对象，判定该方式是否可用。 */
        STRICT_BASELINE,
        /** 严格档关键字组合。 */
        KEYWORD,
        /** 嵌套深度上限。 */
        DEPTH,
        /** 属性数上限。 */
        SIZE,
        /** 枚举规模上限。 */
        ENUM,
        /** 可空（联合类型）。 */
        NULLABLE,
        /** 本地引用（{@code $defs}/{@code $ref}）。 */
        REF,
        /** 中文长文本。 */
        CJK_LONG,
        /** 多模态输入。 */
        MULTIMODAL,
        /** 思考模式开关。 */
        THINKING
    }

    /**
     * 是否作为该生成方式可用性的必要条件（严格档结构要求 S1〜S4：封闭对象、全必填、不递归、规模上限）。
     *
     * <p>关键字（S8：不依赖供应商校验）、中文长文本、多模态与思考模式不作为方式可用性的必要条件，
     * 其结果只影响对应的能力字段。
     */
    public boolean gatesMode() {
        return switch (category) {
            case STRICT_BASELINE, DEPTH, SIZE, ENUM, NULLABLE, REF -> true;
            default -> false;
        };
    }
}

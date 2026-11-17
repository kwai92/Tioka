package com.tioka.runtime.api.context;

/**
 * 上下文段提供者（P3-00b / K1a 注册点）。
 * 组装器（RT-10）按 {@link #zone()} 与 {@link #order()} 调用；新段须在 ADR-048 有名。
 *
 * <p>本接口先行落地；组装器接线等 PK-24b 释放 K1a 后由 P3-00b 完成，行为不变。
 */
public interface ContextSegmentProvider {

    /** 段名（与 ADR-048 表一致）。 */
    String segmentName();

    /** 所属区。 */
    ContextZone zone();

    /** 区内顺序（越小越靠前）。 */
    int order();

    /** 预算份额（相对有效预算 E 的比例，0〜1；0 表示不单独占份额）。 */
    default double budgetShare() {
        return 0d;
    }

    /**
     * GRD-00 / ADR-065 §2.1：本段内容的信任级别。
     *
     * <p>缺省 {@link TrustLevel#INTERNAL}（带来源标注，不加边界），保证既有段提供者行为不变；
     * 上传文档、OCR、未审核知识库、MCP / 网页结果等须显式声明 {@link TrustLevel#EXTERNAL}，
     * 由组装器做每 Turn 随机的边界包裹并置本 Turn 污点。
     */
    default TrustLevel trust() {
        return TrustLevel.INTERNAL;
    }

    /**
     * GRD-00 / ADR-065 §2.2：{@link TrustLevel#EXTERNAL} 段在边界标记中携带的来源引用
     * （如文档单元 ID、工具调用 ID、MCP 名称）。缺省无引用。
     */
    default String sourceRef() {
        return null;
    }

    /**
     * CTX-01：渲染本段的实际文本。默认返回空串，保证既有仅注册元数据的段提供者行为不变。
     *
     * <p>渲染可能触发检索 / 读库，实现方须自行做异常兜底（返回空串而不是抛出），
     * 并自行遵守 {@link #budgetShare()}；组装器仍会做一次统一的预算裁剪。
     * D 区段的结果由组装器按 {@code turnId} 冻结，同一 Turn 内只调用一次。
     */
    default String render(ContextRenderContext context) {
        return "";
    }
}

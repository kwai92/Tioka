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
}

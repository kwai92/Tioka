package com.tioka.runtime.api.spi;

/**
 * 工具可逆性（ADR-033 / TRU-05）。与 {@link com.tioka.runtime.api.approval.ToolRisk} 正交。
 */
public enum Reversibility {
    /** 只读，无副作用。 */
    NONE,
    /** 有对应撤销接口，执行后状态可完全恢复。 */
    REVERSIBLE,
    /** 不能删除痕迹，但有业务冲销动作。 */
    COMPENSABLE,
    /** 发出去就收不回。 */
    IRREVERSIBLE
}

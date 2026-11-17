package com.tioka.governance.api.alert;

/**
 * 告警监控对象类型（GOV-13 / ADR-041 §5.3）。
 *
 * <p>供应商、binding、别名、配额作用对象，或「全部」。
 */
public enum AlertTargetType {
    /** 供应商（provider_id）。 */
    VENDOR,
    /** 模型绑定（binding_id）。 */
    BINDING,
    /** 模型别名（alias 版本）。 */
    ALIAS,
    /** 配额作用对象（{@code target_id} 为 scope 标识，如 workspace/agent/user）。 */
    QUOTA_SCOPE,
    /** 全部对象（跨全部作用对象逐一求值）。 */
    ALL
}

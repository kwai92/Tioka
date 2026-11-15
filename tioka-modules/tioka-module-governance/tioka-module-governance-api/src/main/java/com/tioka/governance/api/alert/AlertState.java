package com.tioka.governance.api.alert;

/** 告警事件状态（GOV-13 / ADR-041 §5.3：同一规则对同一对象只在状态变化时发，恢复时发恢复通知）。 */
public enum AlertState {
    FIRING, RESOLVED
}

package com.tioka.orchestration.api.dispatch;

/**
 * 编排规则动作类型（约束 F23）。
 * M2 开放 {@link #AGENT} 与 {@link #ADMISSION}；{@link #FLOW} 占位，M3 FLW-06 接通。
 */
public enum OrchActionType {
    AGENT,
    ADMISSION,
    FLOW
}

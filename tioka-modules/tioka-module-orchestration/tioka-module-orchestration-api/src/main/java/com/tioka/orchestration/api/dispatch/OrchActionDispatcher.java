package com.tioka.orchestration.api.dispatch;

/**
 * 按动作类型分派（约束 F23）。每种 {@link OrchActionType} 一个实现；
 * {@link OrchActionType#FLOW} 保留位置但 M2 不实现。
 */
public interface OrchActionDispatcher {

    OrchActionType type();

    void dispatch(OrchDispatchCommand command);
}

package com.tioka.orchestration.api.dispatch;

/**
 * {@link OrchActionType#FLOW} 占位：M2 拒绝派工；M3 FLW-06 接通时替换本类。
 */
public final class UnsupportedFlowActionDispatcher implements OrchActionDispatcher {

    @Override
    public OrchActionType type() {
        return OrchActionType.FLOW;
    }

    @Override
    public void dispatch(OrchDispatchCommand command) {
        throw new UnsupportedOperationException("flow 动作在 M3 FLW-06 接通前不可用（约束 F23）");
    }
}

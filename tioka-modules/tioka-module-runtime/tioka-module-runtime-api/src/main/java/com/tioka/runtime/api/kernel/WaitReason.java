package com.tioka.runtime.api.kernel;

/**
 * {@link TurnStatus#WAITING_CHILD} 的等待原因（约束 F24 / RT-09）。
 * 区分子 Turn（含 M3 子 Agent）与后台作业（异步工具结果）。
 */
public enum WaitReason {
    /** 等待子 Turn / 子 Agent。 */
    SUB_TURN,
    /** 等待后台作业 / 异步工具结果。 */
    BACKGROUND_JOB
}

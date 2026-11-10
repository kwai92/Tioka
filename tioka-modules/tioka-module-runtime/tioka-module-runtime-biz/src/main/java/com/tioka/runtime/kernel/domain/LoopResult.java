package com.tioka.runtime.kernel.domain;

import com.tioka.runtime.api.kernel.TurnStatus;

public record LoopResult(TurnStatus status, int promptTokens, int completionTokens,
                         String errorCode, String errorMessage) {

    public static LoopResult completed(int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.COMPLETED, promptTokens, completionTokens, null, null);
    }

    /**
     * SCH-03：COMPLETED 且带原因码（复用 error_code 列承载，如 {@code output_invalid}；
     * ADR-037 §生成、校验与修复 第 5 步，不新增 TurnStatus）。
     */
    public static LoopResult completedWithReason(String reason, int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.COMPLETED, promptTokens, completionTokens, reason, null);
    }

    public static LoopResult failed(String code, String message, int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.FAILED, promptTokens, completionTokens, code, message);
    }

    public static LoopResult cancelled(int promptTokens, int completionTokens) {
        return cancelled(null, promptTokens, completionTokens);
    }

    public static LoopResult cancelled(String reason, int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.CANCELLED, promptTokens, completionTokens, reason, null);
    }

    /** RT-07：已挂起等待审批；TurnRunner 不得写终态。 */
    public static LoopResult waitingApproval(int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.WAITING_APPROVAL, promptTokens, completionTokens, null, null);
    }

    /** RT-09：等待异步子任务；TurnRunner 释放锁与 worker，不得写终态。 */
    public static LoopResult waitingChild(int promptTokens, int completionTokens) {
        return new LoopResult(TurnStatus.WAITING_CHILD, promptTokens, completionTokens, null, null);
    }
}

package com.tioka.runtime.application.trust;

/**
 * TRU-08 撤销熔断阈值（空间可配，M3-05 §6）。缺省：1h 内撤销 3 次 / 补偿失败 1 次。
 * 配置变化经 {@link UndoCircuitBreaker#configure} 写审计。
 */
public record UndoCircuitPolicy(String workspaceId, int undoThreshold,
                                int compFailThreshold, int windowMinutes) {

    public static final int DEFAULT_UNDO_THRESHOLD = 3;
    public static final int DEFAULT_COMP_FAIL_THRESHOLD = 1;
    public static final int DEFAULT_WINDOW_MINUTES = 60;

    public static UndoCircuitPolicy defaults(String workspaceId) {
        return new UndoCircuitPolicy(workspaceId, DEFAULT_UNDO_THRESHOLD,
                DEFAULT_COMP_FAIL_THRESHOLD, DEFAULT_WINDOW_MINUTES);
    }

    public UndoCircuitPolicy {
        if (undoThreshold < 1 || compFailThreshold < 1 || windowMinutes < 1 || windowMinutes > 1440) {
            throw new IllegalArgumentException(
                    "熔断阈值须为正整数，窗口须在 1..1440 分钟之间");
        }
    }
}

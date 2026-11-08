package com.tioka.orchestration.application.decide;

import com.tioka.orchestration.api.engine.ConcurrencyMode;
import com.tioka.orchestration.api.engine.OrchApi;

import java.util.EnumSet;
import java.util.Set;

/**
 * 编排 Run / 工单状态机合法迁移（合入前契约测试覆盖随机步进 + 穷举）。
 */
public final class OrchStateMachine {

    private static final Set<OrchApi.RunStatus> TERMINAL = EnumSet.of(
            OrchApi.RunStatus.SUCCEEDED,
            OrchApi.RunStatus.FAILED,
            OrchApi.RunStatus.CANCELLED);

    private OrchStateMachine() {
    }

    public static boolean isTerminal(OrchApi.RunStatus status) {
        return TERMINAL.contains(status);
    }

    public static boolean canTransit(OrchApi.RunStatus from, OrchApi.RunStatus to) {
        if (from == to) {
            return true;
        }
        if (isTerminal(from)) {
            return false;
        }
        return switch (from) {
            case PENDING -> to == OrchApi.RunStatus.DISPATCHED
                    || to == OrchApi.RunStatus.FAILED
                    || to == OrchApi.RunStatus.CANCELLED;
            case DISPATCHED -> to == OrchApi.RunStatus.RUNNING
                    || to == OrchApi.RunStatus.FAILED
                    || to == OrchApi.RunStatus.CANCELLED
                    || to == OrchApi.RunStatus.SUCCEEDED;
            case RUNNING -> to == OrchApi.RunStatus.WAITING_ATTENTION
                    || to == OrchApi.RunStatus.UNCERTAIN
                    || to == OrchApi.RunStatus.SUCCEEDED
                    || to == OrchApi.RunStatus.FAILED
                    || to == OrchApi.RunStatus.CANCELLED;
            case WAITING_ATTENTION -> to == OrchApi.RunStatus.RUNNING
                    || to == OrchApi.RunStatus.FAILED
                    || to == OrchApi.RunStatus.CANCELLED;
            case UNCERTAIN -> to == OrchApi.RunStatus.RUNNING
                    || to == OrchApi.RunStatus.SUCCEEDED
                    || to == OrchApi.RunStatus.FAILED
                    || to == OrchApi.RunStatus.CANCELLED;
            default -> false;
        };
    }

    /**
     * merge 且已有活动 Run → 只追加 pending，不建 Run。
     */
    public static boolean shouldAppendPendingOnly(ConcurrencyMode mode, boolean hasActiveRun) {
        return mode == ConcurrencyMode.MERGE && hasActiveRun;
    }

    /**
     * serial 且已有活动 Run → 建排队 Run（PENDING，不抢 active）。
     */
    public static boolean shouldQueueSerial(ConcurrencyMode mode, boolean hasActiveRun) {
        return mode == ConcurrencyMode.SERIAL && hasActiveRun;
    }

    /** 是否新建 Run（含 serial 排队）；merge 追加 pending 时为 false。 */
    public static boolean shouldCreateRun(ConcurrencyMode mode, boolean hasActiveRun) {
        return !shouldAppendPendingOnly(mode, hasActiveRun);
    }
}

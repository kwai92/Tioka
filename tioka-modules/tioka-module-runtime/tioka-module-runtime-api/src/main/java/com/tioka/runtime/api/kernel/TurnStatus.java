package com.tioka.runtime.api.kernel;

import java.util.EnumSet;
import java.util.Set;

/**
 * Turn 状态机（ADR-016；WAITING_CHILD 见 ADR-029 第 16 条）。
 *
 * <p>{@link #canTransitionTo} 是唯一合法的状态转换表，实现与契约测试都以此为准。
 */
public enum TurnStatus {
    QUEUED,
    RUNNING,
    /** 等待审批；释放会话锁与 worker，不占并发配额。 */
    WAITING_APPROVAL,
    /** 等待子 Agent 或后台 Job；同上。 */
    WAITING_CHILD,
    /** 租约过期，由回收器决定重新入队或失败。 */
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** 是否占用会话锁与并发配额。 */
    public boolean holdsLease() {
        return this == RUNNING;
    }

    public Set<TurnStatus> allowedNext() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(WAITING_APPROVAL, WAITING_CHILD, INTERRUPTED, COMPLETED, FAILED, CANCELLED);
            case WAITING_APPROVAL, WAITING_CHILD -> EnumSet.of(QUEUED, FAILED, CANCELLED);
            case INTERRUPTED -> EnumSet.of(QUEUED, FAILED, CANCELLED);
            case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(TurnStatus.class);
        };
    }

    public boolean canTransitionTo(TurnStatus next) {
        return allowedNext().contains(next);
    }
}

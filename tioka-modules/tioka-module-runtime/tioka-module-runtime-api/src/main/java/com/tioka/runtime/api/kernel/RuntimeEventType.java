package com.tioka.runtime.api.kernel;

/**
 * SSE 事件类型，小写点分命名（ADR-016、ADR-029 第 2、15 条）。
 *
 * <p>事件 ID 为 {@code {itemSeq}} 或 {@code {itemSeq}.{deltaIndex}}，客户端以 Last-Event-ID 续传。
 */
public enum RuntimeEventType {
    TURN_STARTED("turn.started"),
    TURN_WAITING("turn.waiting"),
    TURN_COMPLETED("turn.completed"),
    TURN_FAILED("turn.failed"),
    TURN_CANCELLED("turn.cancelled"),
    ITEM_CREATED("item.created"),
    ITEM_DELTA("item.delta"),
    ITEM_COMPLETED("item.completed"),
    /** 故障接力时作废已流出一半的 item，前端收到即移除（ADR-029 第 2 条）。 */
    ITEM_DISCARDED("item.discarded"),
    APPROVAL_REQUESTED("approval.requested"),
    APPROVAL_DECIDED("approval.decided");

    private final String wire;

    RuntimeEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}

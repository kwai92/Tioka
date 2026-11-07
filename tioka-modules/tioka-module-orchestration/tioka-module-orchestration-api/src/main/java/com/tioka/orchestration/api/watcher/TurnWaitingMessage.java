package com.tioka.orchestration.api.watcher;

/**
 * {@code tioka.runtime.turn_waiting.v1} payload（orch-turn-watcher 入参）。
 * Schema：{@code docs/contracts/events/tioka.runtime.turn_waiting.v1.json}。
 *
 * @param turnId      Turn ID
 * @param threadId    Thread ID
 * @param reason      等待原因（approval / child / attention）
 * @param origin      来源；watcher 只处理 {@code orch}
 * @param originRef   来源对象 ID（编排侧为 runId）
 * @param workspaceId 空间 ID，可空
 */
public record TurnWaitingMessage(
        String turnId,
        String threadId,
        String reason,
        String origin,
        String originRef,
        String workspaceId) {

    public boolean isOrchOrigin() {
        return TurnFinishedMessage.ORCH_ORIGIN.equals(origin);
    }
}

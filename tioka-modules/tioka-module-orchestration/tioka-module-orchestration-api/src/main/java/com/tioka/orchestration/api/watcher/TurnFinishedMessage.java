package com.tioka.orchestration.api.watcher;

/**
 * {@code tioka.runtime.turn_finished.v1} payload（orch-turn-watcher 入参）。
 * Schema：{@code docs/contracts/events/tioka.runtime.turn_finished.v1.json}。
 *
 * @param turnId      Turn ID
 * @param threadId    Thread ID
 * @param status      Turn 终态（如 COMPLETED / FAILED / CANCELLED）
 * @param origin      来源；watcher 只处理 {@code orch}
 * @param originRef   来源对象 ID（编排侧为 runId）
 * @param errorCode   错误码，可空
 * @param workspaceId 空间 ID，可空
 */
public record TurnFinishedMessage(
        String turnId,
        String threadId,
        String status,
        String origin,
        String originRef,
        String errorCode,
        String workspaceId) {

    public static final String ORCH_ORIGIN = "orch";

    public boolean isOrchOrigin() {
        return ORCH_ORIGIN.equals(origin);
    }
}

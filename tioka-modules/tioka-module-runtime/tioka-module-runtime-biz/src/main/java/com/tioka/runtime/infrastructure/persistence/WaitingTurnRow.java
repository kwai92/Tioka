package com.tioka.runtime.infrastructure.persistence;

/** WAITING_APPROVAL 且无 PENDING 审批的 Turn 行（供 ResumeSweeper）。 */
public record WaitingTurnRow(String id, String threadId) {}

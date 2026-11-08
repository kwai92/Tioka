package com.tioka.runtime.api.event;

/**
 * Turn 进入 {@code COMPLETED} 后由 {@code TurnRunner} 发布（CHT-03 / M2 将迁 Outbox）。
 */
public record TurnCompletedEvent(String turnId, String threadId, String origin, String originRef) {}

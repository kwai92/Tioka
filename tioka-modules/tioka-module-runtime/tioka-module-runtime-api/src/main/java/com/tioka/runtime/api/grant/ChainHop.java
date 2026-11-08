package com.tioka.runtime.api.grant;

/**
 * 委派链上一跳（ADR-039）。
 */
public record ChainHop(String agentVersionId, String taskId) {}

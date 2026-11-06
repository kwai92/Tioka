package com.tioka.agent.api;

/**
 * 发布 / 回滚后 afterCommit 发出，用于清除 {@link AgentSnapshotApi#currentPublished} 缓存。
 */
public record AgentPublishedEvent(String agentId, String versionId) {}

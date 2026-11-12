package com.tioka.agent.api;

import com.tioka.agent.api.spec.AgentSpec;

/**
 * 已发布 / 已退役 Agent 版本快照读取（AST-01）。Runtime / Chat / 审批策略只认快照。
 */
public interface AgentSnapshotApi {

    /** 已发布或已退役的版本快照；不存在 → NOT_FOUND。 */
    AgentSnapshot get(String agentVersionId);

    /** Agent 当前发布指针所指版本；未发布 → NOT_FOUND。 */
    AgentSnapshot currentPublished(String agentId);

    record AgentSnapshot(
            String versionId,
            String agentId,
            int versionNo,
            String workspaceId,
            String ownerId,
            String contentHash,
            AgentSpec spec,
            java.time.Instant publishedAt,
            /** 授权书生命周期状态（TRU-03 / ADR-032；ACTIVE / SUSPENDED / HANDOVER）。 */
            String charterStatus
    ) {
        /** 兼容八参构造（TRU-03 前的调用点；快照读不到授权书状态时按 ACTIVE 口径）。 */
        public AgentSnapshot(String versionId, String agentId, int versionNo, String workspaceId,
                             String ownerId, String contentHash, AgentSpec spec,
                             java.time.Instant publishedAt) {
            this(versionId, agentId, versionNo, workspaceId, ownerId, contentHash, spec,
                    publishedAt, "ACTIVE");
        }
    }
}

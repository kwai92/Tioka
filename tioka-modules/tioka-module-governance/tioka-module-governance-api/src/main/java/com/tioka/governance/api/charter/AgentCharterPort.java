package com.tioka.governance.api.charter;

import java.util.List;

/**
 * Agent 授权书生命周期端口（TRU-03 / M3-05 §4 / ADR-032「负责人与生命周期」）。
 *
 * <p>授权书状态记在 {@code agent_agent.charter_status}（与版本无关），归属 agent 域；
 * 状态机与事件写在 governance-biz {@code CharterLifecycleService}，经本端口读写 Agent 行，
 * 避免 governance-biz 直连 agent 内部表（ADR-030 分层）。
 */
public interface AgentCharterPort {

    /** 指定用户作为负责人的全部 Agent（跨工作空间；调用方须以 SYSTEM 身份绕过行级过滤）。 */
    List<CharterAgentView> listOwnedBy(String userId);

    /** 指定用户在某工作空间内作为负责人的 Agent。 */
    List<CharterAgentView> listOwnedByInWorkspace(String workspaceId, String userId);

    /** 按 ID 读 Agent 授权书面（不存在返回 {@code null}）。 */
    CharterAgentView getAgent(String agentId);

    /**
     * 当前授权书状态；Agent 不存在返回 {@code null}。读直连 DB（不走快照缓存），
     * 供 423 拦截与编排复验保证跨实例即时一致。
     */
    String charterStatus(String agentId);

    /**
     * ACTIVE → SUSPENDED（CAS）。已是 SUSPENDED / HANDOVER 返回 false（幂等：
     * 重复事件只处理一次，不重复写 SUSPEND 事件）。
     */
    boolean transitionToSuspended(String agentId);

    /**
     * ACTIVE / SUSPENDED → HANDOVER 并更换负责人（空间管理员指定新负责人）。
     * 无可转移状态返回 false。
     */
    boolean transitionToHandover(String agentId, String newOwnerId);

    /** HANDOVER → ACTIVE 并刷新 {@code reviewed_at}（新负责人确认授权书）。非 HANDOVER 返回 false。 */
    boolean transitionToActive(String agentId);

    /** 该 Agent 可读版本（PUBLISHED / RETIRED）的版本 ID，用于运行中 Turn 取消的 thread 反查。 */
    List<String> readableVersionIds(String agentId);

    /** @param charterStatus ACTIVE / SUSPENDED / HANDOVER（{@link CharterStatus} name）。 */
    record CharterAgentView(String agentId, String workspaceId, String ownerId, String charterStatus) {}
}

package com.tioka.agent.infrastructure.persistence;

import java.time.LocalDateTime;

/**
 * {@code agent_version} 时间线重建读回行（OBS-12 / ADR-075 §4 Agent 主体时间线）。
 *
 * <p>只取时间线需要的列，并左连 {@code agent_agent} 补齐空间——{@code agent_version} 无
 * {@code workspace_id} 列（workspace-scope-exempt），条目空间须从所属 Agent 带出，
 * 否则投影条目 {@code workspace_id} 为空会绕过查询侧空间隔离。
 *
 * <p>本类无 {@code @TableName}，不参与 ScopeLine 表扫描（同 {@code AuditEventTimelineDO}）。
 */
public class AgentVersionTimelineDO {
    private String id;
    private String agentId;
    private Integer versionNo;
    private String status;
    private String note;
    private String publishedBy;
    /** 左连 {@code agent_agent} 补齐的空间。 */
    private String workspaceId;
    /** 条目发生时间：{@code COALESCE(published_at, create_at)}。 */
    private LocalDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}

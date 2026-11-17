package com.tioka.knowledge.infrastructure.persistence;

import java.time.LocalDateTime;

/**
 * {@code kb_document} 时间线重建读回行（OBS-12 / ADR-075 §4 知识文档主体时间线）。
 *
 * <p>只取时间线需要的列，并左连 {@code kb_base} 补齐空间——{@code kb_document} 无
 * {@code workspace_id} 列（workspace-scope-exempt），条目空间须从所属知识库带出，
 * 否则投影条目 {@code workspace_id} 为空会绕过查询侧空间隔离。
 *
 * <p>本类无 {@code @TableName}，不参与 ScopeLine 表扫描（同 {@code AuditEventTimelineDO}）。
 */
public class KbDocumentTimelineDO {
    private String id;
    private String kbId;
    private String title;
    private String status;
    private String dataLabel;
    private String errorMessage;
    private String parseStatus;
    private Integer activeVersion;
    /** 左连 {@code kb_base} 补齐的空间。 */
    private String workspaceId;
    /** 条目发生时间：{@code COALESCE(update_at, create_at)}。 */
    private LocalDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public Integer getActiveVersion() { return activeVersion; }
    public void setActiveVersion(Integer activeVersion) { this.activeVersion = activeVersion; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}

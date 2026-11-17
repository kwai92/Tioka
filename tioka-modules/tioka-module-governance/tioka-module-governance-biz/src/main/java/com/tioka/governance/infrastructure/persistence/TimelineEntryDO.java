package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.foundation.starter.data.AppendOnlyDO;

import java.time.Instant;

/**
 * {@code timeline_entry}（V229 / ADR-075 §2 统一时间线投影）。只增不改；投影不是事实源，
 * 校验时回到 {@code sourceType} / {@code sourceId} 指回的原记录。
 */
@TableName("timeline_entry")
public class TimelineEntryDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private Instant occurredAt;
    private Integer seq;
    private String requestId;
    private String parentRequestId;
    private String category;
    private String title;
    private String summary;
    private String actor;
    private String outcome;
    private String sourceType;
    private String sourceId;
    private String traceId;
    private String workspaceId;
    private String dataLabel;
    private String sourceEventId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getParentRequestId() { return parentRequestId; }
    public void setParentRequestId(String parentRequestId) { this.parentRequestId = parentRequestId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }
}

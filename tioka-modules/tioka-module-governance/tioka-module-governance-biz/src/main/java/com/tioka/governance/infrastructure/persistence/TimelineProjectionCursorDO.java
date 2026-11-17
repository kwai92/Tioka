package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.Instant;

/**
 * {@code timeline_projection_cursor}（V229 / ADR-075 §2）：按来源类型记录重建进度。
 * 平台级技术状态，无 {@code workspace_id}，已登记 {@code tioka.persistence.workspace-scope-exempt-tables}。
 */
@TableName("timeline_projection_cursor")
public class TimelineProjectionCursorDO extends TechnicalRecordDO {

    @TableId(type = IdType.INPUT)
    private String sourceType;
    private Instant cursorTs;
    private String cursorId;
    private String lastEventId;
    private Instant updatedAt;

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Instant getCursorTs() { return cursorTs; }
    public void setCursorTs(Instant cursorTs) { this.cursorTs = cursorTs; }
    public String getCursorId() { return cursorId; }
    public void setCursorId(String cursorId) { this.cursorId = cursorId; }
    public String getLastEventId() { return lastEventId; }
    public void setLastEventId(String lastEventId) { this.lastEventId = lastEventId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

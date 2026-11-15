package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/**
 * {@code timeline_entry_subject}（V229 / ADR-075 §2）：时间线条目与主体的多值关联。
 */
@TableName("timeline_entry_subject")
public class TimelineEntrySubjectDO extends AppendOnlyDO {

    private String entryId;
    private String subjectType;
    private String subjectId;
    private String workspaceId;

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
}

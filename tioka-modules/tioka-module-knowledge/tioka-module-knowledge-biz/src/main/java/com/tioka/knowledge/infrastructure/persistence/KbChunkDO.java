package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("kb_chunk")
public class KbChunkDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String docId;
    private String kbId;
    /** V118：冗余空间 ID，供检索 ACL / 行级过滤下推。 */
    private String workspaceId;
    private String text;
    private Integer ordinal;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
}

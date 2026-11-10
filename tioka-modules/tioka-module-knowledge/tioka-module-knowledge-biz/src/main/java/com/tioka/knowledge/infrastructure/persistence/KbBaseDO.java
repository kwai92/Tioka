package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

@TableName("kb_base")
public class KbBaseDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String embeddingBindingId;
    /** DAT-02（V164）：知识库数据标签，缺省 INTERNAL。 */
    private String dataLabel;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmbeddingBindingId() { return embeddingBindingId; }
    public void setEmbeddingBindingId(String embeddingBindingId) { this.embeddingBindingId = embeddingBindingId; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
}

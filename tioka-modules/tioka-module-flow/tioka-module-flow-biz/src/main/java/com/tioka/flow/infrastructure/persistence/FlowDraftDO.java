package com.tioka.flow.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

@TableName("flow_draft")
public class FlowDraftDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String graphJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGraphJson() { return graphJson; }
    public void setGraphJson(String graphJson) { this.graphJson = graphJson; }
}

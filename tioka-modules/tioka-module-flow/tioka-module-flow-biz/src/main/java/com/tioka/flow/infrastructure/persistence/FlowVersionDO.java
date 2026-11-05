package com.tioka.flow.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.ImmutableSnapshotDO;
@TableName("flow_version")
public class FlowVersionDO extends ImmutableSnapshotDO {
    @TableId(type = IdType.INPUT)
        private String id;
    private String draftId;
    private String name;
    private String graphJson;
    private Boolean published;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDraftId() { return draftId; }
    public void setDraftId(String draftId) { this.draftId = draftId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGraphJson() { return graphJson; }
    public void setGraphJson(String graphJson) { this.graphJson = graphJson; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
}

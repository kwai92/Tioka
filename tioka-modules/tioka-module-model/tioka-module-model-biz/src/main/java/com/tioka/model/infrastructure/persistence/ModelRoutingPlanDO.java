package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.ImmutableSnapshotDO;

@TableName("model_routing_plan")
public class ModelRoutingPlanDO extends ImmutableSnapshotDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String type;
    private String primaryBindingId;
    private String fallbackBindingId;
    private String hardBindingId;
    private Boolean published;
    private String snapshotHash;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPrimaryBindingId() { return primaryBindingId; }
    public void setPrimaryBindingId(String primaryBindingId) { this.primaryBindingId = primaryBindingId; }
    public String getFallbackBindingId() { return fallbackBindingId; }
    public void setFallbackBindingId(String fallbackBindingId) { this.fallbackBindingId = fallbackBindingId; }
    public String getHardBindingId() { return hardBindingId; }
    public void setHardBindingId(String hardBindingId) { this.hardBindingId = hardBindingId; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
}

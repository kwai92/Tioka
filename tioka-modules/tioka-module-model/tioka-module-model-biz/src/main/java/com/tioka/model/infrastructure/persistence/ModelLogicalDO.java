package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("model_logical")
public class ModelLogicalDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String capabilityTags;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapabilityTags() { return capabilityTags; }
    public void setCapabilityTags(String capabilityTags) { this.capabilityTags = capabilityTags; }
}

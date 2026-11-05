package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("model_binding")
public class ModelBindingDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String logicalModelId;
    private String providerId;
    private String modelName;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(String logicalModelId) { this.logicalModelId = logicalModelId; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}

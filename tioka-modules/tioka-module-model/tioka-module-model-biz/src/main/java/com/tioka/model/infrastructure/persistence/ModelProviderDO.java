package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("model_provider")
public class ModelProviderDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String baseUrl;
    private String apiKeyRef;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyRef() { return apiKeyRef; }
    public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }
}

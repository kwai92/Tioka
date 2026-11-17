package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.foundation.starter.data.BaseDO;

@TableName("model_provider")
public class ModelProviderDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String baseUrl;
    private String apiKeyRef;
    /** GOV-12（V150）：当前生效版本指针。 */
    private String currentVersionId;
    private Integer currentVersionNo;
    /** MH-10（V236 / ADR-041 §5.1）：运行健康状态结论（DISABLED / BUDGET_EXHAUSTED / COOLDOWN / DOWN / DEGRADED / UP）。 */
    private String health;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyRef() { return apiKeyRef; }
    public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }
    public String getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(String currentVersionId) { this.currentVersionId = currentVersionId; }
    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }
    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }
}

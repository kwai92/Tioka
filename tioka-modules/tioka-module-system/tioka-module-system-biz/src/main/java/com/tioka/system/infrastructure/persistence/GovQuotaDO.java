package com.tioka.system.infrastructure.persistence;

import java.time.OffsetDateTime;

/**
 * {@code gov_quota} 行（V102；无逻辑删，不继承 BaseDO）。
 */
public class GovQuotaDO {
    private String scopeType;
    private String scopeId;
    private String metric;
    private Long limitValue;
    private Long usedValue;
    private OffsetDateTime windowStart;
    private OffsetDateTime createAt;
    private OffsetDateTime updateAt;
    private String createBy;
    private String updateBy;

    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getLimitValue() { return limitValue; }
    public void setLimitValue(Long limitValue) { this.limitValue = limitValue; }
    public Long getUsedValue() { return usedValue; }
    public void setUsedValue(Long usedValue) { this.usedValue = usedValue; }
    public OffsetDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(OffsetDateTime windowStart) { this.windowStart = windowStart; }
    public OffsetDateTime getCreateAt() { return createAt; }
    public void setCreateAt(OffsetDateTime createAt) { this.createAt = createAt; }
    public OffsetDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(OffsetDateTime updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}

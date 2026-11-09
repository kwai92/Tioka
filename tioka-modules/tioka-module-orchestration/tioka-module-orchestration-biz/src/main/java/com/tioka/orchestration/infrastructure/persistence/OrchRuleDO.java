package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

import java.time.LocalDateTime;

/** V132 后规则头：空间级资源（workspace_id + owner_id）。 */
@TableName("orch_rule")
public class OrchRuleDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String stableVersionId;
    private String canaryVersionId;

    /** 连续失败自动暂停（V131 列；ORC-07）：暂停开始时间，null = 未暂停。 */
    private LocalDateTime pausedAt;
    /** 暂停原因，如 consecutive_failures。 */
    private String pausedReason;
    /** 连续失败计数：Run FAILED +1，SUCCEEDED 清零。 */
    private Integer consecutiveFailures;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStableVersionId() { return stableVersionId; }
    public void setStableVersionId(String stableVersionId) { this.stableVersionId = stableVersionId; }
    public String getCanaryVersionId() { return canaryVersionId; }
    public void setCanaryVersionId(String canaryVersionId) { this.canaryVersionId = canaryVersionId; }
    public LocalDateTime getPausedAt() { return pausedAt; }
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }
    public String getPausedReason() { return pausedReason; }
    public void setPausedReason(String pausedReason) { this.pausedReason = pausedReason; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
}

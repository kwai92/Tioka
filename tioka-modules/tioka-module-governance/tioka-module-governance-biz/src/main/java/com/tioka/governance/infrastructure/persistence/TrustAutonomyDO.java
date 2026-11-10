package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code trust_autonomy}（V203 / ADR-034「数据模型」）；等级 per Agent×类别。
 * 复合主键（agent_id, action_class）不走 {@code @TableId}，查询一律经仓储显式条件。
 */
@TableName("trust_autonomy")
public class TrustAutonomyDO extends TechnicalRecordDO {

    private String agentId;
    private String actionClass;
    private String level;
    private LocalDateTime observeUntil;
    private LocalDateTime promotionBlockedUntil;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getActionClass() { return actionClass; }
    public void setActionClass(String actionClass) { this.actionClass = actionClass; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public LocalDateTime getObserveUntil() { return observeUntil; }
    public void setObserveUntil(LocalDateTime observeUntil) { this.observeUntil = observeUntil; }
    public LocalDateTime getPromotionBlockedUntil() { return promotionBlockedUntil; }
    public void setPromotionBlockedUntil(LocalDateTime promotionBlockedUntil) { this.promotionBlockedUntil = promotionBlockedUntil; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}

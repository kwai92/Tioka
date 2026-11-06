package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 各用途默认路由方案（V117 {@code model_default_plan}）。
 * {@code update_at} 为 TIMESTAMPTZ，映射 {@link OffsetDateTime}。
 */
@TableName("model_default_plan")
public class ModelDefaultPlanDO {
    @TableId(type = IdType.INPUT)
    private String purpose;
    private String routingPlanId;
    private String bindingId;
    private OffsetDateTime updateAt;
    private String updateBy;

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getRoutingPlanId() { return routingPlanId; }
    public void setRoutingPlanId(String routingPlanId) { this.routingPlanId = routingPlanId; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public OffsetDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(OffsetDateTime updateAt) { this.updateAt = updateAt; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}

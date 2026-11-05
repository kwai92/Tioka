package com.tioka.model.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("decision_binding")
public class DecisionBindingDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String bindingId;
    private String purpose;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}

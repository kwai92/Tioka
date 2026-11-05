package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("model_session_freeze")
public class ModelSessionFreezeDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String sessionId;
    private String bindingId;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
}

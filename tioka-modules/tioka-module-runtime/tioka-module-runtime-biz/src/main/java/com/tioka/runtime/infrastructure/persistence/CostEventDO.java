package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("cost_event")
public class CostEventDO extends AppendOnlyDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String atTs;
    private String modelRef;
    private Integer promptTokens;
    private Integer completionTokens;
    private String reason;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getAtTs() { return atTs; }
    public void setAtTs(String atTs) { this.atTs = atTs; }
    public String getModelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

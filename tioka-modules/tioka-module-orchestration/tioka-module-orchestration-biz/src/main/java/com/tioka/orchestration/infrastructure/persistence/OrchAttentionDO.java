package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("orch_attention")
public class OrchAttentionDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String status;
    private String title;
    private String cardJson;
    private String answerJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCardJson() { return cardJson; }
    public void setCardJson(String cardJson) { this.cardJson = cardJson; }
    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }
}

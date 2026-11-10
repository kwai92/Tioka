package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("runtime_item")
public class RuntimeItemDO extends AppendOnlyDO {
    private String threadId;
    private Long seq;
    private String id;
    private String turnId;
    private Integer step;
    private String type;
    private String role;
    private String contentJson;
    private Integer tokenCount;
    /** DAT-02（V164）：工具结果携带的数据标签（M3-02）；null = 未打标。 */
    private String dataLabel;
    private Boolean discarded;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Integer getStep() { return step; }
    public void setStep(Integer step) { this.step = step; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
    public Boolean getDiscarded() { return discarded; }
    public void setDiscarded(Boolean discarded) { this.discarded = discarded; }
}

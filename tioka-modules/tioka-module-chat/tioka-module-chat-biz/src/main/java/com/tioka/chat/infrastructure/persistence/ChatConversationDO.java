package com.tioka.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

import java.time.OffsetDateTime;

@TableName("chat_conversation")
public class ChatConversationDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String agentVersionId;
    private String runtimeSessionId;
    private OffsetDateTime lastActiveAt;
    private String lastPreview;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getRuntimeSessionId() { return runtimeSessionId; }
    public void setRuntimeSessionId(String runtimeSessionId) { this.runtimeSessionId = runtimeSessionId; }
    public OffsetDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(OffsetDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public String getLastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
}

package com.tioka.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("chat_message")
public class ChatMessageDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String role;
    private String text;
    private String partsJson;
    private Long createdOrd;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getPartsJson() { return partsJson; }
    public void setPartsJson(String partsJson) { this.partsJson = partsJson; }
    public Long getCreatedOrd() { return createdOrd; }
    public void setCreatedOrd(Long createdOrd) { this.createdOrd = createdOrd; }
}

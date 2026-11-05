package com.tioka.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("chat_memory")
public class ChatMemoryDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String memKey;
    private String memValue;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getMemKey() { return memKey; }
    public void setMemKey(String memKey) { this.memKey = memKey; }
    public String getMemValue() { return memValue; }
    public void setMemValue(String memValue) { this.memValue = memValue; }
}

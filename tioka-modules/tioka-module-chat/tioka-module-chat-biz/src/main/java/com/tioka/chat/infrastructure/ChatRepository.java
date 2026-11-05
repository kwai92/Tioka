package com.tioka.chat.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.chat.infrastructure.mapper.ChatConversationMapper;
import com.tioka.chat.infrastructure.mapper.ChatMemoryMapper;
import com.tioka.chat.infrastructure.mapper.ChatMessageMapper;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.chat.infrastructure.persistence.ChatMemoryDO;
import com.tioka.chat.infrastructure.persistence.ChatMessageDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChatRepository {
    private final ChatConversationMapper conversations;
    private final ChatMessageMapper messages;
    private final ChatMemoryMapper memories;

    public ChatRepository(ChatConversationMapper conversations, ChatMessageMapper messages, ChatMemoryMapper memories) {
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
    }

    public void insertConversation(ChatConversationDO row) { conversations.insert(row); }

    public ChatConversationDO findConversation(String id) { return conversations.selectById(id); }

    public boolean hasConversation(String id) { return conversations.selectById(id) != null; }

    public List<ChatConversationDO> listConversations() {
        return conversations.selectList(Wrappers.<ChatConversationDO>lambdaQuery().orderByAsc(ChatConversationDO::getId));
    }

    public void insertMessage(ChatMessageDO row) { messages.insert(row); }

    public List<ChatMessageDO> listMessages(String conversationId) {
        return messages.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getConversationId, conversationId)
                .orderByAsc(ChatMessageDO::getCreatedOrd));
    }

    public void upsertMemory(ChatMemoryDO row) {
        if (memories.selectById(row.getId()) == null) memories.insert(row);
        else memories.updateById(row);
    }

    public String recallMemory(String conversationId, String key) {
        ChatMemoryDO row = memories.selectOne(Wrappers.<ChatMemoryDO>lambdaQuery()
                .eq(ChatMemoryDO::getConversationId, conversationId)
                .eq(ChatMemoryDO::getMemKey, key));
        return row == null ? null : row.getMemValue();
    }
}

package com.tioka.chat.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.chat.infrastructure.mapper.ChatMemoryMapper;
import com.tioka.chat.infrastructure.persistence.ChatMemoryDO;
import org.springframework.stereotype.Repository;

@Repository
public class ChatMemoryRepository {
    private final ChatMemoryMapper mapper;
    public ChatMemoryRepository(ChatMemoryMapper mapper) { this.mapper = mapper; }

    public void upsert(String conversationId, String key, String value) {
        ChatMemoryDO existing = mapper.selectOne(Wrappers.<ChatMemoryDO>lambdaQuery()
                .eq(ChatMemoryDO::getConversationId, conversationId).eq(ChatMemoryDO::getMemKey, key));
        if (existing == null) {
            ChatMemoryDO row = new ChatMemoryDO();
            row.setConversationId(conversationId); row.setMemKey(key); row.setMemValue(value);
            // composite: use conversationId as id surrogate for TableId - set id field wrongly
            row.setId(conversationId + "::" + key);
            mapper.insert(row);
        } else {
            existing.setMemValue(value);
            mapper.updateById(existing);
        }
    }

    public String find(String conversationId, String key) {
        ChatMemoryDO row = mapper.selectOne(Wrappers.<ChatMemoryDO>lambdaQuery()
                .eq(ChatMemoryDO::getConversationId, conversationId).eq(ChatMemoryDO::getMemKey, key));
        return row == null ? null : row.getMemValue();
    }
}

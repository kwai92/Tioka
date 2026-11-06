package com.tioka.chat.application;

import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatMemoryDO;
import org.springframework.stereotype.Service;

@Service
public class ChatDensityService {
    private final ChatRepository chatRepository;

    public ChatDensityService(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    public void remember(String conversationId, String key, String value) {
        ChatMemoryDO row = new ChatMemoryDO();
        row.setId(conversationId + ":" + key);
        row.setConversationId(conversationId);
        row.setMemKey(key);
        row.setMemValue(value);
        chatRepository.upsertMemory(row);
    }

    public String recall(String conversationId, String key) {
        return chatRepository.recallMemory(conversationId, key);
    }
}

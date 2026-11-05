package com.tioka.chat.application;

import com.tioka.chat.domain.ChatStore;
import com.tioka.chat.domain.ChatStore.Conversation;
import com.tioka.chat.domain.ChatStore.Message;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatMemoryDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ChatDensityService {
    private final ChatStore store;
    private final ChatRepository chatRepository;

    public ChatDensityService(ChatStore store, ChatRepository chatRepository) {
        this.store = store;
        this.chatRepository = chatRepository;
    }

    public Conversation fork(String conversationId) {
        Conversation c = store.getConversation(conversationId);
        if (c == null) throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        String id = "conv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Conversation fork = new Conversation(id, c.title() + " (fork)", c.agentVersionId(), c.runtimeSessionId());
        store.saveConversation(fork);
        for (Message m : store.messages(conversationId)) {
            store.addMessage(new Message(
                    "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    id, m.role(), m.text(), m.parts()));
        }
        return fork;
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

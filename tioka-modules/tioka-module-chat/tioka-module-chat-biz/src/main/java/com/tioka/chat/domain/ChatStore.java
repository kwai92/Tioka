package com.tioka.chat.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.chat.infrastructure.persistence.ChatMessageDO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ChatStore {
    private final ChatRepository repo;
    private final ObjectMapper mapper;
    private final AtomicLong ord = new AtomicLong(System.currentTimeMillis());

    public ChatStore(ChatRepository repo, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repo = repo;
    }

    public void saveConversation(Conversation c) {
        ChatConversationDO row = new ChatConversationDO();
        row.setId(c.id());
        row.setTitle(c.title());
        row.setAgentVersionId(c.agentVersionId());
        row.setRuntimeSessionId(c.runtimeSessionId());
        repo.insertConversation(row);
    }

    public Conversation getConversation(String id) {
        ChatConversationDO row = repo.findConversation(id);
        return row == null ? null
                : new Conversation(row.getId(), row.getTitle(), row.getAgentVersionId(), row.getRuntimeSessionId());
    }

    public boolean hasConversation(String id) {
        return repo.hasConversation(id);
    }

    public List<Conversation> listConversations() {
        return repo.listConversations().stream()
                .map(r -> new Conversation(r.getId(), r.getTitle(), r.getAgentVersionId(), r.getRuntimeSessionId()))
                .toList();
    }

    public void deleteConversation(String id) {
        repo.deleteConversation(id);
    }

    public void addMessage(Message m) {
        ChatMessageDO row = new ChatMessageDO();
        row.setId(m.id());
        row.setConversationId(m.conversationId());
        row.setRole(m.role());
        row.setText(m.text());
        row.setPartsJson(toJson(m.parts()));
        row.setCreatedOrd(ord.incrementAndGet());
        repo.insertMessage(row);
    }

    public List<Message> messages(String conversationId) {
        return repo.listMessages(conversationId).stream()
                .map(r -> new Message(r.getId(), r.getConversationId(), r.getRole(), r.getText(), fromJson(r.getPartsJson())))
                .toList();
    }

    private String toJson(List<Map<String, Object>> parts) {
        try {
            return mapper.writeValueAsString(parts == null ? List.of() : parts);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "partsJson 解析失败");
        }
    }

    public record Conversation(String id, String title, String agentVersionId, String runtimeSessionId) {}
    public record Message(String id, String conversationId, String role, String text, List<Map<String, Object>> parts) {}
}

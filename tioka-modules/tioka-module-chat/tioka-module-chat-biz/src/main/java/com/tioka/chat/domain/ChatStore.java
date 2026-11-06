package com.tioka.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.chat.infrastructure.persistence.ChatMessageDO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
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
        row.setRuntimeSessionId(c.threadId());
        row.setLastActiveAt(OffsetDateTime.now());
        repo.insertConversation(row);
    }

    public Conversation getConversation(String id) {
        ChatConversationDO row = repo.findConversation(id);
        return row == null ? null : toConversation(row);
    }

    public boolean hasConversation(String id) {
        return repo.hasConversation(id);
    }

    public List<Conversation> listConversations() {
        return repo.listConversations().stream().map(this::toConversation).toList();
    }

    public void deleteConversation(String id) {
        repo.deleteConversation(id);
    }

    /** CHT-02：更新 last_active_at；首条消息时可顺带改标题。 */
    public void touchActivity(String conversationId, String newTitleOrNull) {
        repo.touchActivity(conversationId, newTitleOrNull, OffsetDateTime.now());
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

    private Conversation toConversation(ChatConversationDO row) {
        return new Conversation(row.getId(), row.getTitle(), row.getAgentVersionId(), row.getRuntimeSessionId());
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

    /**
     * @param threadId Runtime thread（库列仍为 runtime_session_id）；JSON 同时暴露 threadId 与兼容字段 runtimeSessionId
     */
    public record Conversation(
            String id,
            String title,
            String agentVersionId,
            @JsonProperty("threadId") String threadId
    ) {
        @JsonProperty("runtimeSessionId")
        public String runtimeSessionId() {
            return threadId;
        }
    }

    public record Message(String id, String conversationId, String role, String text, List<Map<String, Object>> parts) {}
}

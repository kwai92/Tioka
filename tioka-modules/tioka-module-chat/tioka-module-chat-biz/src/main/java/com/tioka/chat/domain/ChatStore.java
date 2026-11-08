package com.tioka.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Chat 应用侧门面（薄）。持久化经 {@link ChatRepository}。
 */
@Repository
public class ChatStore {
    private final ChatRepository repo;

    public ChatStore(ChatRepository repo) {
        this.repo = repo;
    }

    public void saveConversation(Conversation c) {
        ChatConversationDO row = new ChatConversationDO();
        row.setId(c.id());
        row.setTitle(c.title());
        row.setAgentVersionId(c.agentVersionId());
        row.setRuntimeThreadId(c.threadId());
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

    /** CHT-03：Turn 完成后回写会话预览（不校验归属，派发线程可能无 Principal）。 */
    public void updateLastPreview(String conversationId, String preview) {
        repo.updateLastPreview(conversationId, preview);
    }

    public String findConversationIdByThreadId(String threadId) {
        return repo.findConversationIdByThreadId(threadId);
    }

    private Conversation toConversation(ChatConversationDO row) {
        return new Conversation(row.getId(), row.getTitle(), row.getAgentVersionId(), row.getRuntimeThreadId());
    }

    /**
     * @param threadId Runtime Thread id（库列 {@code runtime_thread_id}）；JSON 仅暴露 {@code threadId}
     */
    public record Conversation(
            String id,
            String title,
            String agentVersionId,
            @JsonProperty("threadId") String threadId
    ) {
    }

    /**
     * 卡片 API 临时响应 DTO（非持久化产品 Message；见 47-GL CardMessage）。
     */
    public record CardMessage(String id, String conversationId, String role, String text,
                              List<Map<String, Object>> parts) {}

    /** @deprecated 使用 {@link CardMessage} */
    @Deprecated
    public record Message(String id, String conversationId, String role, String text,
                          List<Map<String, Object>> parts) {
        public CardMessage toCardMessage() {
            return new CardMessage(id, conversationId, role, text, parts);
        }
    }
}

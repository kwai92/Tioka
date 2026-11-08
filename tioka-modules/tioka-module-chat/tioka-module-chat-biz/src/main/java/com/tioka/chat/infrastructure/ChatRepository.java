package com.tioka.chat.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.chat.infrastructure.mapper.ChatConversationMapper;
import com.tioka.chat.infrastructure.mapper.ChatMemoryMapper;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.chat.infrastructure.persistence.ChatMemoryDO;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ChatRepository {
    private final ChatConversationMapper conversations;
    private final ChatMemoryMapper memories;
    private final PrincipalHolder principals;

    public ChatRepository(ChatConversationMapper conversations,
                          ChatMemoryMapper memories, PrincipalHolder principals) {
        this.conversations = conversations;
        this.memories = memories;
        this.principals = principals;
    }

    public void insertConversation(ChatConversationDO row) { conversations.insert(row); }

    public ChatConversationDO findConversation(String id) {
        ChatConversationDO row = conversations.selectById(id);
        if (row == null || !visibleToCurrent(row)) {
            return null;
        }
        return row;
    }

    public boolean hasConversation(String id) { return findConversation(id) != null; }

    public List<ChatConversationDO> listConversations() {
        var q = Wrappers.<ChatConversationDO>lambdaQuery().orderByAsc(ChatConversationDO::getId);
        Principal p = principals.find().orElse(null);
        if (p != null && p.type() != PrincipalType.SYSTEM) {
            q.eq(ChatConversationDO::getOwnerId, p.userId());
        }
        return conversations.selectList(q);
    }

    public void deleteConversation(String id) {
        if (findConversation(id) == null) {
            return;
        }
        // @TableLogic：逻辑删（禁止物理 DELETE）
        conversations.deleteById(id);
    }

    public void touchActivity(String conversationId, String newTitleOrNull, OffsetDateTime lastActiveAt) {
        ChatConversationDO row = findConversation(conversationId);
        if (row == null) {
            return;
        }
        row.setLastActiveAt(lastActiveAt);
        if (newTitleOrNull != null && !newTitleOrNull.isBlank()) {
            row.setTitle(newTitleOrNull);
        }
        conversations.updateById(row);
    }

    /** 按主键更新预览，不走归属过滤（Turn 派发线程可能无 Principal）。 */
    public void updateLastPreview(String conversationId, String preview) {
        ChatConversationDO row = conversations.selectById(conversationId);
        if (row == null) {
            return;
        }
        row.setLastPreview(preview);
        conversations.updateById(row);
    }

    /** 按 thread（runtime_thread_id）反查会话 ID；派发线程无 originRef 时兜底。 */
    public String findConversationIdByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        ChatConversationDO row = conversations.selectOne(Wrappers.<ChatConversationDO>lambdaQuery()
                .eq(ChatConversationDO::getRuntimeThreadId, threadId)
                .last("LIMIT 1"));
        return row == null ? null : row.getId();
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

    private boolean visibleToCurrent(ChatConversationDO row) {
        Principal p = principals.find().orElse(null);
        if (p == null || p.type() == PrincipalType.SYSTEM) {
            return true;
        }
        return p.userId().equals(row.getOwnerId());
    }
}

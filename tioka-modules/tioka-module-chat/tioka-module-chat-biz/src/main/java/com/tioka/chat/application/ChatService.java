package com.tioka.chat.application;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.chat.domain.ChatStore;
import com.tioka.chat.domain.ChatStore.Conversation;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.model.api.CostQueryApi;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.system.api.audit.Audited;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * CHT-02：Chat 发送异步化。{@code send} 只校验归属 → {@code startTurn} → 更新索引字段，不写 chat_message、不等模型。
 */
@Service
public class ChatService {
    private final AgentSnapshotApi snapshots;
    private final RuntimeApi runtime;
    private final CostQueryApi costs;
    private final ChatStore store;

    public ChatService(AgentSnapshotApi snapshots,
                       RuntimeApi runtime,
                       CostQueryApi costs,
                       ChatStore store) {
        this.snapshots = snapshots;
        this.runtime = runtime;
        this.costs = costs;
        this.store = store;
    }

    public CreatedConversation create(String title, String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "agentVersionId 必填");
        }
        snapshots.get(agentVersionId); // 校验已发布版本存在
        String id = Ids.newId("conv");
        String resolvedTitle = title == null || title.isBlank() ? "新会话" : title;
        RuntimeApi.ThreadView thread = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                agentVersionId, "chat", id, resolvedTitle, Map.of()));
        Conversation c = new Conversation(id, resolvedTitle, agentVersionId, thread.threadId());
        store.saveConversation(c);
        return new CreatedConversation(c.id(), c.threadId(), c.title());
    }

    public List<Conversation> list() {
        return store.listConversations();
    }

    public Conversation get(String conversationId) {
        Conversation c = store.getConversation(conversationId);
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return c;
    }

    /** GOV-05：会话成本按 threadId 归集。 */
    public CostQueryApi.CostBucket cost(String conversationId) {
        Conversation c = get(conversationId);
        return costs.sessionCost(c.threadId());
    }

    public void delete(String conversationId) {
        get(conversationId);
        store.deleteConversation(conversationId);
    }

    /**
     * 异步发送：立即返回；按 clientMsgId 幂等。
     */
    public SendAccepted send(String conversationId, String text, String clientMsgId) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "text 必填");
        }
        if (clientMsgId == null || clientMsgId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "clientMsgId 必填");
        }
        Conversation c = get(conversationId);
        boolean first = runtime.listItems(c.threadId(), 0, 1).isEmpty();
        RuntimeApi.TurnHandle handle = runtime.startTurn(c.threadId(), new RuntimeApi.StartTurnCmd(
                clientMsgId,
                List.of(RuntimeApi.InputItem.text(text)),
                null,
                "chat",
                conversationId));
        String newTitle = null;
        if (first) {
            String preview = text.trim();
            newTitle = preview.length() <= 30 ? preview : preview.substring(0, 30);
        }
        store.touchActivity(conversationId, newTitle);
        return new SendAccepted(handle.turnId(), c.threadId(), handle.eventsUrl());
    }

    public List<RuntimeApi.ItemView> messages(String conversationId, long afterSeq, int limit) {
        Conversation c = get(conversationId);
        return runtime.listItems(c.threadId(), afterSeq, limit);
    }

    /** 停止当前活动 Turn；无活动 Turn 时幂等成功。 */
    @Transactional
    @Audited(action = "chat.stop", resourceType = "conversation", resourceIdArg = "#conversationId")
    public void stop(String conversationId) {
        Conversation c = get(conversationId);
        runtime.findActiveTurn(c.threadId()).ifPresent(t -> runtime.interrupt(t.turnId()));
    }

    public record CreatedConversation(String id, String threadId, String title) {}

    public record SendAccepted(String turnId, String threadId, String eventsUrl) {}
}

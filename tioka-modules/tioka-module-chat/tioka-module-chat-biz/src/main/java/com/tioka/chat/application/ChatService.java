package com.tioka.chat.application;

import com.tioka.agent.api.AgentQueryApi;
import com.tioka.chat.domain.ChatStore;
import com.tioka.chat.domain.ChatStore.Conversation;
import com.tioka.chat.domain.ChatStore.Message;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.RuntimeSessionApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private final AgentQueryApi agentQueryApi;
    private final RuntimeSessionApi runtime;
    private final ChatStore store;
    private final PrincipalHolder principals;
    private final TransactionTemplate tx;

    public ChatService(AgentQueryApi agentQueryApi,
                       RuntimeSessionApi runtime,
                       ChatStore store,
                       PrincipalHolder principals,
                       PlatformTransactionManager transactionManager) {
        this.agentQueryApi = agentQueryApi;
        this.runtime = runtime;
        this.store = store;
        this.principals = principals;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Conversation create(String title, String agentVersionId) {
        AgentQueryApi.AgentVersionView v = agentQueryApi.requirePublished(agentVersionId);
        List<RuntimeSessionApi.SkillInstallInput> skills = v.skills() == null ? List.of() : v.skills().stream()
                .map(s -> new RuntimeSessionApi.SkillInstallInput(
                        s.skillId(), s.name(), s.mode(), s.description(), s.skillMd()))
                .toList();
        String workspaceId = principals.current().workspaceId();
        var session = runtime.create(new RuntimeSessionApi.CreateSessionRequest(
                v.id(), v.routingPlanId(),
                Map.of("agentVersionId", v.id(), "workspaceId", workspaceId == null ? "" : workspaceId),
                skills));
        String id = Ids.newId("conv");
        Conversation c = new Conversation(id, title == null ? "新会话" : title, agentVersionId, session.id());
        store.saveConversation(c);
        return c;
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

    public void delete(String conversationId) {
        get(conversationId);
        store.deleteConversation(conversationId);
    }

    public Message send(String conversationId, String text, String clientMsgId) {
        Conversation c = get(conversationId);
        String mid = Ids.newId("msg");
        Message user = new Message(mid, conversationId, "user", text, List.of(Map.of("type", "text", "text", text)));
        // GOV-04：用户消息先落库提交；模型调用不在事务内。与 startTurn 同事务待 RuntimeApi 适配后合并。
        tx.executeWithoutResult(status -> store.addMessage(user));

        RuntimeSessionApi.TurnView turn = runtime.append(c.runtimeSessionId(),
                new RuntimeSessionApi.AppendRequest(clientMsgId, text));
        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();
        for (RuntimeSessionApi.PartView p : turn.parts()) {
            parts.add(Map.of(
                    "id", p.id(),
                    "type", p.type(),
                    "text", p.text() == null ? "" : p.text(),
                    "payload", p.payload() == null ? Map.of() : p.payload()));
            if ("text".equals(p.type()) && p.payload() != null && Boolean.FALSE.equals(p.payload().get("delta"))) {
                assistantText.append(p.text());
            } else if ("tool_result".equals(p.type())) {
                assistantText.append("\n[tool] ").append(p.text());
            }
        }
        Message asst = new Message(Ids.newId("msg"),
                conversationId, "assistant", assistantText.toString().trim(), parts);
        store.addMessage(asst);
        return asst;
    }

    public List<Message> messages(String conversationId) {
        get(conversationId);
        return store.messages(conversationId);
    }
}

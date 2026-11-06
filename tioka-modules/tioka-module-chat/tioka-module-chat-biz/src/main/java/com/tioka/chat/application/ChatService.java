package com.tioka.chat.application;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.chat.domain.ChatStore;
import com.tioka.chat.domain.ChatStore.Conversation;
import com.tioka.chat.domain.ChatStore.Message;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.skill.api.SkillQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private final AgentSnapshotApi snapshots;
    private final SkillQueryApi skills;
    private final RuntimeSessionApi runtime;
    private final ChatStore store;
    private final PrincipalHolder principals;
    private final TransactionTemplate tx;

    public ChatService(AgentSnapshotApi snapshots,
                       SkillQueryApi skills,
                       RuntimeSessionApi runtime,
                       ChatStore store,
                       PrincipalHolder principals,
                       PlatformTransactionManager transactionManager) {
        this.snapshots = snapshots;
        this.skills = skills;
        this.runtime = runtime;
        this.store = store;
        this.principals = principals;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Conversation create(String title, String agentVersionId) {
        AgentSnapshotApi.AgentSnapshot snap = snapshots.get(agentVersionId);
        List<RuntimeSessionApi.SkillInstallInput> skillInputs = new ArrayList<>();
        for (AgentSpec.SkillRef ref : snap.spec().skills()) {
            skills.findPublished(ref.skillVersionId()).ifPresent(sk ->
                    skillInputs.add(new RuntimeSessionApi.SkillInstallInput(
                            sk.id(), sk.name(),
                            ref.mode() == null ? "ALWAYS" : ref.mode(),
                            sk.description(), sk.skillMd())));
        }
        String workspaceId = principals.current().workspaceId();
        var session = runtime.create(new RuntimeSessionApi.CreateSessionRequest(
                snap.versionId(), snap.spec().model().routingPlanId(),
                Map.of("agentVersionId", snap.versionId(), "workspaceId", workspaceId == null ? "" : workspaceId),
                skillInputs));
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

package com.tioka.runtime.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.infrastructure.LocalObjectStore;
import com.tioka.runtime.infrastructure.LocalWorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-07：绑定 skill 后 append 的 messages 含 [skill:name] 文本。
 */
class SkillBindingInjectTest {
    @TempDir Path tmp;

    @Test
    void appendInjectsSkillSystemMessage() throws Exception {
        LocalObjectStore objects = new LocalObjectStore(tmp.resolve("obj").toString());
        LocalWorkspaceFs fs = LocalWorkspaceFs.forTest(tmp.resolve("ws"), objects);
        FakeSessionRepo repo = new FakeSessionRepo();

        ModelRouterApi router = new ModelRouterApi() {
            @Override
            public ModelSelection select(String sessionId, String routingPlanId) {
                return select(sessionId, routingPlanId, null);
            }

            @Override
            public ModelSelection select(String sessionId, String routingPlanId, String userText) {
                return new ModelSelection("m@x", "p", "b", "http://mock", "ref", "x", "primary");
            }

            @Override
            public ModelSelection failover(String sessionId, String routingPlanId, String reason) {
                throw new UnsupportedOperationException();
            }
        };

        AtomicReference<List<Map<String, Object>>> captured = new AtomicReference<>();
        LlmClientApi llm = new LlmClientApi() {
            @Override
            public CompletionResult complete(String baseUrl, String apiKeyRef, String model,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools) {
                return new CompletionResult("ok", List.of(), 1, 1);
            }

            @Override
            public CompletionResult streamComplete(String baseUrl, String apiKeyRef, String model,
                                                   List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools,
                                                   java.util.function.Consumer<StreamDelta> onDelta) {
                captured.set(messages);
                return new CompletionResult("ok", List.of(), 1, 1);
            }
        };

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, new DefaultToolGateway(new ToolRegistry(List.of())),
                fs, new SkillInstallService(fs),
                event -> {}, repo, new com.fasterxml.jackson.databind.ObjectMapper());

        var session = svc.create(new RuntimeSessionApi.CreateSessionRequest(
                "av_x", "rp_x", Map.of("workspaceId", "ws_sk"),
                List.of(new RuntimeSessionApi.SkillInstallInput(
                        "sk1", "pptx", "always", "Make PPT", "SKILL_BODY_SECRET"))));

        svc.append(session.id(), new RuntimeSessionApi.AppendRequest("c1", "hello"));

        List<Map<String, Object>> messages = captured.get();
        assertNotNull(messages);
        assertTrue(messages.stream().anyMatch(m ->
                "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("[skill:pptx]")
                        && String.valueOf(m.get("content")).contains("SKILL_BODY_SECRET")));
    }

    static final class FakeSessionRepo extends RuntimeSessionRepository {
        private final Map<String, RuntimeSessionDO> store = new ConcurrentHashMap<>();

        FakeSessionRepo() { super(null, null); }

        @Override public void upsert(RuntimeSessionDO row) { store.put(row.getId(), row); }
        @Override public RuntimeSessionDO find(String id) { return store.get(id); }
        @Override public String findTurnId(String sessionId, String clientMsgId) { return null; }
        @Override public void insertIdem(RuntimeIdemDO row) {}
    }
}

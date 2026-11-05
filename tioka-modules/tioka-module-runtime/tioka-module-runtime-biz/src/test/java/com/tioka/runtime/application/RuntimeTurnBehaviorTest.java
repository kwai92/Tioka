package com.tioka.runtime.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.api.spi.WorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTurnBehaviorTest {

    @Test
    void sameClientMsgId_returnsSameTurnPartsOnly() throws Exception {
        AtomicInteger selects = new AtomicInteger();
        ModelRouterApi router = mock(ModelRouterApi.class);
        when(router.select(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            selects.incrementAndGet();
            return sel();
        });
        LlmClientApi llm = textLlm("hello");
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.asOpenAiTools(any())).thenReturn(List.of());
        SkillInstallService skills = mock(SkillInstallService.class);
        when(skills.promptFragments(any())).thenReturn(List.of());

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, gateway, mock(WorkspaceFs.class), skills,
                mock(ApplicationEventPublisher.class), new FakeIdemRepo());

        var session = svc.create(new RuntimeSessionApi.CreateSessionRequest("av", "rp", Map.of()));
        var t1 = svc.append(session.id(), new RuntimeSessionApi.AppendRequest("c1", "hi"));
        var t2 = svc.append(session.id(), new RuntimeSessionApi.AppendRequest("c1", "hi"));
        assertEquals(t1.turnId(), t2.turnId());
        assertEquals(t1.parts(), t2.parts());
        assertEquals(1, selects.get());
    }

    @Test
    void sameTurn_toolContinuation_reusesModelSelection() throws Exception {
        AtomicInteger selects = new AtomicInteger();
        ModelRouterApi router = mock(ModelRouterApi.class);
        when(router.select(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            selects.incrementAndGet();
            return sel();
        });
        LlmClientApi llm = mock(LlmClientApi.class);
        AtomicInteger llmCalls = new AtomicInteger();
        when(llm.streamComplete(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            int n = llmCalls.incrementAndGet();
            if (n == 1) {
                return new LlmClientApi.CompletionResult("", List.of(
                        new LlmClientApi.ToolCall("tc1", "echo", "{\"text\":\"x\"}")), 1, 1);
            }
            return new LlmClientApi.CompletionResult("done", List.of(), 1, 1);
        });
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.asOpenAiTools(any())).thenReturn(List.of());
        when(gateway.call(any(), anyString(), any()))
                .thenReturn(new ToolProvider.ToolResult(true, "x"));
        SkillInstallService skills = mock(SkillInstallService.class);
        when(skills.promptFragments(any())).thenReturn(List.of());

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, gateway, mock(WorkspaceFs.class), skills,
                mock(ApplicationEventPublisher.class), new FakeIdemRepo());
        var session = svc.create(new RuntimeSessionApi.CreateSessionRequest("av", "rp", Map.of()));
        svc.append(session.id(), new RuntimeSessionApi.AppendRequest("c2", "use tool"));
        assertEquals(1, selects.get());
        verify(router, times(1)).select(anyString(), anyString(), anyString());
    }

    @Test
    void cancel_stopsPersistingFinalAssistantPart() throws Exception {
        ModelRouterApi router = mock(ModelRouterApi.class);
        when(router.select(anyString(), anyString(), anyString())).thenReturn(sel());
        final String[] sid = new String[1];
        final RuntimeSessionService[] box = new RuntimeSessionService[1];
        LlmClientApi llm = mock(LlmClientApi.class);
        when(llm.streamComplete(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            box[0].cancel(sid[0]);
            @SuppressWarnings("unchecked")
            Consumer<LlmClientApi.StreamDelta> onDelta = inv.getArgument(5);
            onDelta.accept(LlmClientApi.StreamDelta.text("late"));
            return new LlmClientApi.CompletionResult("should-not-persist", List.of(), 1, 1);
        });
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.asOpenAiTools(any())).thenReturn(List.of());
        SkillInstallService skills = mock(SkillInstallService.class);
        when(skills.promptFragments(any())).thenReturn(List.of());

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, gateway, mock(WorkspaceFs.class), skills,
                mock(ApplicationEventPublisher.class), new FakeIdemRepo());
        box[0] = svc;
        var session = svc.create(new RuntimeSessionApi.CreateSessionRequest("av", "rp", Map.of()));
        sid[0] = session.id();
        var turn = svc.append(session.id(), new RuntimeSessionApi.AppendRequest("c3", "hi"));
        assertEquals("CANCELLED", turn.status());
        boolean hasFinal = turn.parts().stream()
                .anyMatch(p -> "text".equals(p.type())
                        && Boolean.FALSE.equals(p.payload().get("delta"))
                        && "should-not-persist".equals(p.text()));
        assertTrue(!hasFinal, "cancelled turn must not persist final assistant part");
    }

    private static ModelRouterApi.ModelSelection sel() {
        return new ModelRouterApi.ModelSelection("m@x", "p", "b", "http://m", "ref", "m", "primary");
    }

    private static LlmClientApi textLlm(String text) throws Exception {
        LlmClientApi llm = mock(LlmClientApi.class);
        when(llm.streamComplete(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<LlmClientApi.StreamDelta> onDelta = inv.getArgument(5);
            onDelta.accept(LlmClientApi.StreamDelta.text(text));
            return new LlmClientApi.CompletionResult(text, List.of(), 1, 1);
        });
        return llm;
    }

    static final class FakeIdemRepo extends RuntimeSessionRepository {
        private final Map<String, String> idem = new ConcurrentHashMap<>();
        private final Map<String, RuntimeSessionDO> store = new ConcurrentHashMap<>();

        FakeIdemRepo() {
            super(null, null);
        }

        @Override
        public void upsert(RuntimeSessionDO row) {
            store.put(row.getId(), row);
        }

        @Override
        public RuntimeSessionDO find(String id) {
            return store.get(id);
        }

        @Override
        public void insertIdem(RuntimeIdemDO row) {
            idem.put(row.getSessionId() + ":" + row.getClientMsgId(), row.getTurnId());
        }

        @Override
        public String findTurnId(String sessionId, String clientMsgId) {
            return idem.get(sessionId + ":" + clientMsgId);
        }
    }
}

package com.tioka.runtime.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.WorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSessionPinVersionTest {

    @Test
    void create_pinsAgentVersionId_forSessionLifetime() throws Exception {
        ModelRouterApi router = mock(ModelRouterApi.class);
        LlmClientApi llm = mock(LlmClientApi.class);
        ToolGateway gateway = mock(ToolGateway.class);
        WorkspaceFs fs = mock(WorkspaceFs.class);
        SkillInstallService skills = mock(SkillInstallService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RuntimeSessionRepository sessions = mock(RuntimeSessionRepository.class);

        when(skills.promptFragments(any())).thenReturn(List.of());
        when(gateway.asOpenAiTools(any())).thenReturn(List.of());
        when(router.select(anyString(), anyString(), anyString()))
                .thenReturn(new ModelRouterApi.ModelSelection(
                        "m@x", "p", "b", "http://mock", "ref", "m", "primary"));
        when(llm.streamComplete(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Consumer<LlmClientApi.StreamDelta> onDelta = inv.getArgument(5);
                    onDelta.accept(LlmClientApi.StreamDelta.text("ok"));
                    return new LlmClientApi.CompletionResult("ok", List.of(), 1, 1);
                });

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, gateway, fs, skills, events, sessions);

        var view = svc.create(new RuntimeSessionApi.CreateSessionRequest(
                "av_v1", "rp_1", Map.of("workspaceId", "ws_1")));
        assertEquals("av_v1", view.agentVersionId());
        assertEquals("av_v1", svc.get(view.id()).agentVersionId());

        svc.append(view.id(), new RuntimeSessionApi.AppendRequest("c1", "hi"));
        assertEquals("av_v1", svc.get(view.id()).agentVersionId());
    }
}

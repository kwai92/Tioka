package com.tioka.runtime.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.infrastructure.LocalObjectStore;
import com.tioka.runtime.infrastructure.LocalWorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G-FS：Session create 挂载工作盘后，跨轮次 scratch 可复用。
 */
class WorkspaceSessionScratchTest {
    @TempDir Path tmp;

    @Test
    void scratchSurvivesAcrossTurns() throws Exception {
        LocalObjectStore objects = new LocalObjectStore(tmp.resolve("obj").toString());
        LocalWorkspaceFs fs = LocalWorkspaceFs.forTest(tmp.resolve("ws"), objects);
        RuntimeSessionRepository repo = mock(RuntimeSessionRepository.class);
        when(repo.find(any())).thenReturn(null);

        ModelRouterApi router = mock(ModelRouterApi.class);
        LlmClientApi llm = mock(LlmClientApi.class);
        when(llm.streamComplete(any(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmClientApi.CompletionResult("ok", List.of(), 1, 1));

        RuntimeSessionService svc = new RuntimeSessionService(
                router, llm, new DefaultToolGateway(new ToolRegistry(List.of())),
                fs, new SkillInstallService(fs),
                event -> {}, repo);

        var session = svc.create(new RuntimeSessionApi.CreateSessionRequest("av_x", "rp_x",
                Map.of("workspaceId", "ws_gfs")));
        String ws = svc.workspaceId(session.id());
        fs.writeBytes(ws, session.id(), "scratch/a.txt", "v1".getBytes(StandardCharsets.UTF_8));
        assertEquals("v1", new String(fs.readBytes(ws, session.id(), "scratch/a.txt"), StandardCharsets.UTF_8));
        fs.writeBytes(ws, session.id(), "scratch/a.txt", "v2".getBytes(StandardCharsets.UTF_8));
        assertEquals("v2", new String(fs.readBytes(ws, session.id(), "scratch/a.txt"), StandardCharsets.UTF_8));
    }
}

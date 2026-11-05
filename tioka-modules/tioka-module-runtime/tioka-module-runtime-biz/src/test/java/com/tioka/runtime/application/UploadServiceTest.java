package com.tioka.runtime.application;

import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.runtime.api.RuntimeSessionApi;
import com.tioka.runtime.infrastructure.LocalObjectStore;
import com.tioka.runtime.infrastructure.LocalObjectStoreAdapter;
import com.tioka.runtime.infrastructure.LocalWorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadServiceTest {
    @TempDir Path tmp;

    @Test
    void materializeProducesFileRefWithoutBase64InLogicalPath() throws Exception {
        LocalObjectStore store = new LocalObjectStore(tmp.resolve("obj").toString());
        LocalWorkspaceFs fs = LocalWorkspaceFs.forTest(tmp.resolve("ws"), store);
        RuntimeSessionRepository repo = mock(RuntimeSessionRepository.class);
        when(repo.find(any())).thenReturn(null);
        RuntimeSessionService sessions = new RuntimeSessionService(
                mock(ModelRouterApi.class), mock(LlmClientApi.class),
                new DefaultToolGateway(new ToolRegistry(List.of())),
                fs, new SkillInstallService(fs), e -> {}, repo);
        var session = sessions.create(new RuntimeSessionApi.CreateSessionRequest("av", "rp", Map.of("workspaceId", "ws1")));

        UploadService uploads = new UploadService(new LocalObjectStoreAdapter(store), fs, sessions);
        var pre = uploads.presign("shot.png", "image/png", session.id());
        uploads.putContent(pre.uploadId(), "PNGDATA".getBytes(StandardCharsets.UTF_8));
        var conf = uploads.confirm(pre.uploadId(), session.id());
        assertTrue(conf.logicalPath().startsWith("uploads/"));
        assertFalse(conf.fileRefPart().toString().contains("PNGDATA"));
        String onDisk = new String(fs.readBytes("ws1", session.id(), conf.logicalPath()), StandardCharsets.UTF_8);
        assertEquals("PNGDATA", onDisk);

        fs.writeBytes("ws1", session.id(), "artifacts/out/report.txt", "OUT".getBytes(StandardCharsets.UTF_8));
        var pub = uploads.publish(session.id(), "artifacts/out/report.txt", "text/plain");
        assertNotNull(pub.downloadTicket());
        assertEquals("OUT", new String(uploads.downloadByTicket(pub.downloadTicket()), StandardCharsets.UTF_8));
    }
}

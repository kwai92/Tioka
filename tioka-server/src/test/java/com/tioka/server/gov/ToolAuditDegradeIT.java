package com.tioka.server.gov;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.application.audit.AuditService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * OBS-01：工具审计写库失败时落入降级 JSONL。
 */
@SpringBootTest
@Tag("it")
class ToolAuditDegradeIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired ToolGateway gateway;
    @Autowired AuditDegradedSink degraded;
    @SpyBean AuditService auditService;

    @Test
    void auditWriteFailureAppendsDegradedFile() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("*"), "password");

        doThrow(new RuntimeException("forced-audit-fail")).when(auditService).record(any(AuditEvent.class));
        doThrow(new RuntimeException("forced-audit-fail"))
                .when(auditService).recordIndependent(any(AuditEvent.class));

        if (Files.exists(degraded.path())) {
            Files.delete(degraded.path());
        }

        ToolProvider.ToolResult result = principals.runAs(p, () ->
                gateway.call(ToolCallContexts.forTurn(
                                p, "thr-obs01", "turn-obs01", "", "chat", List.of(), Map.of()),
                        "kb.search", Map.of("query", "x")));
        assertFalse(result.ok(), "无 agentVersion 应拒绝调用，但仍走审计路径");

        assertTrue(Files.exists(degraded.path()), "应写出降级文件: " + degraded.path());
        String body = Files.readString(degraded.path());
        assertTrue(body.contains("tool.invoke"), body);
        assertTrue(body.contains("\"seq\""), body);
    }
}

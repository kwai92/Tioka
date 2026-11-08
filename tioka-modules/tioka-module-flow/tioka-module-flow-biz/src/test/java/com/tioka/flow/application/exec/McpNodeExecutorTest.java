package com.tioka.flow.application.exec;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpNodeExecutorTest {

    @Test
    @SuppressWarnings("unchecked")
    void routesThroughToolGateway() {
        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.call(any(), eq("echo"), any()))
                .thenReturn(new ToolProvider.ToolResult(true, "ok"));
        ObjectProvider<ToolGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);

        McpNodeExecutor exec = new McpNodeExecutor(provider);
        Principal runAs = new Principal("1", "ws_a", PrincipalType.USER, Set.of(), Set.of(), "pwd");
        NodeExecContext ctx = new NodeExecContext("run_1", runAs, Map.of());
        ctx.beginNodeRun("nr_1");

        Object out = exec.execute(ctx, Map.of(
                "config", Map.of("serverId", "srv_1", "tool", "echo", "arguments", Map.of("text", "hi"))),
                null);

        assertTrue(out instanceof Map<?, ?>);
        Map<String, Object> map = (Map<String, Object>) out;
        assertEquals(true, map.get("ok"));
        assertEquals("ok", map.get("content"));
        verify(gateway).call(any(), eq("echo"), eq(Map.of("text", "hi")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsMissingServerId() {
        ObjectProvider<ToolGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(ToolGateway.class));
        McpNodeExecutor exec = new McpNodeExecutor(provider);
        Object out = exec.execute(new NodeExecContext("run_1", Map.of()),
                Map.of("config", Map.of("tool", "echo")), null);
        Map<String, Object> map = (Map<String, Object>) out;
        assertFalse((Boolean) map.get("ok"));
        assertTrue(String.valueOf(map.get("error")).contains("serverId"));
    }
}

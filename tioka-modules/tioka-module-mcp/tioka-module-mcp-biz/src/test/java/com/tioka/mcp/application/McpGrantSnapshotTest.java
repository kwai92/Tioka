package com.tioka.mcp.application;

import com.tioka.agent.api.AgentQueryApi;
import com.tioka.framework.error.BizException;
import com.tioka.mcp.domain.McpStore;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpGrantSnapshotTest {

    @Test
    void toolsOutsideVersionGrant_forbidden() {
        McpStore store = mock(McpStore.class);
        SecretStore secrets = mock(SecretStore.class);
        when(store.getGrant("gr_a")).thenReturn(Optional.of(
                new McpStore.Grant("gr_a", "agent_1", "srv_1", List.of("tool_a"))));
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("tool_a", "a", Map.of()),
                new McpStore.Tool("tool_b", "b", Map.of())));

        McpAdminService admin = new McpAdminService(secrets, store, true);
        assertThrows(BizException.class,
                () -> admin.callToolWithGrantIds(List.of("gr_a"), "tool_b", Map.of()));
    }

    @Test
    void toolsForGrantIds_respectsAllowlist() {
        McpStore store = mock(McpStore.class);
        when(store.getGrant("gr_a")).thenReturn(Optional.of(
                new McpStore.Grant("gr_a", "agent_1", "srv_1", List.of("tool_a"))));
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("tool_a", "a", Map.of()),
                new McpStore.Tool("tool_b", "b", Map.of())));

        McpAdminService admin = new McpAdminService(mock(SecretStore.class), store, true);
        List<McpStore.Tool> tools = admin.toolsForGrantIds(List.of("gr_a"));
        assertEquals(1, tools.size());
        assertEquals("tool_a", tools.get(0).name());
    }

    @Test
    void provider_usesVersionGrantSnapshot_notAgentId() {
        AgentQueryApi agents = mock(AgentQueryApi.class);
        when(agents.requirePublished("av_1")).thenReturn(
                new AgentQueryApi.AgentVersionView("av_1", "agent_x", "i", "rp", List.of("gr_a")));

        McpStore store = mock(McpStore.class);
        when(store.getGrant("gr_a")).thenReturn(Optional.of(
                new McpStore.Grant("gr_a", "agent_other", "srv_1", List.of("mcp_echo"))));
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("mcp_echo", "echo", Map.of("type", "object"))));
        when(store.getServer("srv_1")).thenReturn(Optional.of(
                new McpStore.McpServer("srv_1", "s", "mock://x", null, "READY"))); // record in McpStore

        McpAdminService admin = new McpAdminService(mock(SecretStore.class), store, true);
        McpToolProvider provider = new McpToolProvider(admin, agents);

        assertTrue(provider.listTools("av_1").stream().anyMatch(t -> t.name().equals("mcp_echo")));
        assertTrue(provider.listTools("av_1").size() == 1);
        var r = provider.invoke("av_1", "mcp_echo", Map.of("text", "hi"));
        assertTrue(r.ok());
        assertEquals("mcp:hi", r.content());
    }
}

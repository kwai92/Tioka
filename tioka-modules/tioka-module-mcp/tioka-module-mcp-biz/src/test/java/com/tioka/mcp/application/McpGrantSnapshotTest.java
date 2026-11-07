package com.tioka.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.mcp.domain.McpStore;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpGrantSnapshotTest {

    @Test
    void callToolWithMcpBindings_respectsAllowlist() {
        McpStore store = mock(McpStore.class);
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("tool_a", "a", Map.of()),
                new McpStore.Tool("tool_b", "b", Map.of())));
        McpAdminService admin = new McpAdminService(mock(SecretStore.class), store, true, new ObjectMapper());

        assertThrows(BizException.class, () ->
                admin.callToolWithMcpBindings(
                        List.of(new McpAdminService.McpBinding("srv_1", List.of("tool_a"))),
                        "tool_b", Map.of()));
    }

    @Test
    void toolsForMcpBindings_respectsAllowlist() {
        McpStore store = mock(McpStore.class);
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("tool_a", "a", Map.of()),
                new McpStore.Tool("tool_b", "b", Map.of())));
        McpAdminService admin = new McpAdminService(mock(SecretStore.class), store, true, new ObjectMapper());

        List<McpStore.Tool> tools = admin.toolsForMcpBindings(
                List.of(new McpAdminService.McpBinding("srv_1", List.of("tool_a"))));
        assertEquals(1, tools.size());
        assertEquals("tool_a", tools.get(0).name());
    }

    @Test
    void toolsForGrantIds_readsMemoryGrant() {
        McpStore store = mock(McpStore.class);
        when(store.getGrant("gr_a")).thenReturn(Optional.of(
                new McpStore.Grant("gr_a", "agent_x", "srv_1", List.of("tool_a"))));
        when(store.tools("srv_1")).thenReturn(List.of(
                new McpStore.Tool("tool_a", "a", Map.of()),
                new McpStore.Tool("tool_b", "b", Map.of())));
        McpAdminService admin = new McpAdminService(mock(SecretStore.class), store, true, new ObjectMapper());
        assertEquals(1, admin.toolsForGrantIds(List.of("gr_a")).size());
    }
}

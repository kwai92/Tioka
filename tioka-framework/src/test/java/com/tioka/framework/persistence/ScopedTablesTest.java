package com.tioka.framework.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedTablesTest {

    @Test
    void defaultExemptBypassesAuthAndColumnlessTables() {
        ScopedTables tables = new ScopedTables(new PersistenceProperties());
        for (String t : Set.of("auth_user", "runtime_item", "sys_member", "sys_api_key",
                "kb_document", "mcp_tool", "flow_publication", "orch_rule_version", "orch_decision")) {
            assertTrue(tables.isExempt(t), t);
        }
    }

    @Test
    void spaceAssetsAreNotExempt() {
        ScopedTables tables = new ScopedTables(new PersistenceProperties());
        for (String t : Set.of(
                "agent_agent", "kb_base", "kb_chunk", "flow_draft", "mcp_server",
                "skill_package", "orch_run", "chat_conversation", "runtime_thread",
                "runtime_approval", "model_usage", "audit_event")) {
            assertFalse(tables.isExempt(t), t);
            assertTrue(tables.workspaceScoped(t), t);
        }
    }

    @Test
    void customExemptOverridesDefaults() {
        PersistenceProperties props = new PersistenceProperties();
        props.setWorkspaceScopeExemptTables(List.of("only_this"));
        ScopedTables tables = new ScopedTables(props);
        assertTrue(tables.isExempt("only_this"));
        assertFalse(tables.isExempt("auth_user"));
    }
}

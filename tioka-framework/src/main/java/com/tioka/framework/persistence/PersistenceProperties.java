package com.tioka.framework.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "tioka.persistence")
public class PersistenceProperties {
    private List<String> workspaceScopedTables = new ArrayList<>(List.of(
            "agent_agent",
            "kb_base",
            "flow_draft",
            "mcp_server",
            "skill_package",
            "orch_run",
            "chat_conversation",
            "runtime_session",
            "runtime_approval"
    ));

    public List<String> getWorkspaceScopedTables() {
        return workspaceScopedTables;
    }

    public void setWorkspaceScopedTables(List<String> workspaceScopedTables) {
        this.workspaceScopedTables = workspaceScopedTables;
    }
}

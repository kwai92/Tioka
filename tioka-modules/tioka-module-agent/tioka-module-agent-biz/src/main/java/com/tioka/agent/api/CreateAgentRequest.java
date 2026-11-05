package com.tioka.agent.api;

import jakarta.validation.constraints.NotBlank;

public class CreateAgentRequest {
    @NotBlank
    private String name;
    private String workspaceId = "ws_default";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWorkspaceId() {
        return workspaceId == null || workspaceId.isBlank() ? "ws_default" : workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }
}

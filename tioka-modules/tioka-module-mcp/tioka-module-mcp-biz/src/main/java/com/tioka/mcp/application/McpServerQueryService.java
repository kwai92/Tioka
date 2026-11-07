package com.tioka.mcp.application;

import com.tioka.mcp.api.McpServerQueryApi;
import com.tioka.mcp.domain.McpStore;
import com.tioka.mcp.infrastructure.McpRepository;
import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class McpServerQueryService implements McpServerQueryApi {
    private final McpRepository repo;
    private final McpStore store;
    private final McpHealthService health;

    public McpServerQueryService(McpRepository repo, McpStore store, McpHealthService health) {
        this.repo = repo;
        this.store = store;
        this.health = health;
    }

    @Override
    public Optional<ServerRef> lockServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Optional.empty();
        }
        McpServerDO row = repo.lockServerForShare(serverId);
        if (row == null) {
            return Optional.empty();
        }
        boolean circuitOpen = "CIRCUIT_OPEN".equals(health.check(serverId).status());
        return Optional.of(new ServerRef(row.getId(), row.getStatus(), circuitOpen));
    }

    @Override
    public boolean hasTool(String serverId, String toolName) {
        if (serverId == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        return store.findTool(serverId, toolName).isPresent();
    }

    @Override
    public List<ServerSummary> listSummaries() {
        return store.servers().stream()
                .map(s -> new ServerSummary(s.id(), s.name()))
                .toList();
    }
}

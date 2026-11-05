package com.tioka.mcp.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.mcp.infrastructure.McpRepository;
import com.tioka.mcp.infrastructure.persistence.McpGrantDO;
import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import com.tioka.mcp.infrastructure.persistence.McpToolDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class McpStore {
    private final McpRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpStore(McpRepository repo) {
        this.repo = repo;
    }

    public void putServer(McpServer s) {
        McpServerDO row = new McpServerDO();
        row.setId(s.id());
        row.setName(s.name());
        row.setBaseUrl(s.baseUrl());
        row.setHeaderSecretRef(s.headerSecretRef());
        row.setStatus(s.status());
        repo.upsertServer(row);
    }

    public Optional<McpServer> getServer(String id) {
        McpServerDO row = repo.findServer(id);
        return row == null ? Optional.empty()
                : Optional.of(new McpServer(row.getId(), row.getName(), row.getBaseUrl(), row.getHeaderSecretRef(), row.getStatus()));
    }

    public List<McpServer> servers() {
        return repo.listServers().stream()
                .map(r -> new McpServer(r.getId(), r.getName(), r.getBaseUrl(), r.getHeaderSecretRef(), r.getStatus()))
                .toList();
    }

    public void replaceTools(String serverId, List<Tool> tools) {
        List<McpToolDO> rows = tools.stream().map(t -> {
            McpToolDO row = new McpToolDO();
            row.setId(serverId + ":" + t.name());
            row.setServerId(serverId);
            row.setName(t.name());
            row.setDescription(t.description());
            row.setInputSchema(toJson(t.inputSchema()));
            return row;
        }).toList();
        repo.replaceTools(serverId, rows);
    }

    public List<Tool> tools(String serverId) {
        return repo.listTools(serverId).stream()
                .map(r -> new Tool(r.getName(), r.getDescription(), fromMap(r.getInputSchema())))
                .toList();
    }

    public void putGrant(Grant g) {
        McpGrantDO row = new McpGrantDO();
        row.setId(g.id());
        row.setAgentId(g.agentId());
        row.setServerId(g.serverId());
        row.setAllowlist(toJsonList(g.allowlist()));
        repo.upsertGrant(row);
    }

    public List<Grant> grants() {
        return repo.listGrants().stream()
                .map(r -> new Grant(r.getId(), r.getAgentId(), r.getServerId(), fromList(r.getAllowlist())))
                .toList();
    }

    public Optional<Grant> getGrant(String id) {
        McpGrantDO row = repo.findGrant(id);
        return row == null ? Optional.empty()
                : Optional.of(new Grant(row.getId(), row.getAgentId(), row.getServerId(), fromList(row.getAllowlist())));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJsonList(List<String> list) {
        try {
            return mapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> fromList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public record McpServer(String id, String name, String baseUrl, String headerSecretRef, String status) {}
    public record Tool(String name, String description, Map<String, Object> inputSchema) {}
    public record Grant(String id, String agentId, String serverId, List<String> allowlist) {}
}

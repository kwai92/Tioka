package com.tioka.mcp.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.mcp.infrastructure.McpRepository;
import com.tioka.mcp.infrastructure.persistence.McpServerDO;
import com.tioka.mcp.infrastructure.persistence.McpToolDO;
import org.springframework.stereotype.Repository;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class McpStore {
    private final McpRepository repo;
    private final ObjectMapper mapper;

    public McpStore(McpRepository repo, ObjectMapper mapper) {
        this.mapper = mapper;
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

    /**
     * 全量替换工具列表。同名工具保留已配置 {@code risk}；新工具默认 WRITE（FE-01）。
     */
    public void replaceTools(String serverId, List<Tool> tools) {
        Map<String, String> existingRisk = new HashMap<>();
        for (McpToolDO old : repo.listTools(serverId)) {
            if (old.getRisk() != null && !old.getRisk().isBlank()) {
                existingRisk.put(old.getName(), old.getRisk());
            }
        }
        List<McpToolDO> rows = tools.stream().map(t -> {
            McpToolDO row = new McpToolDO();
            row.setId(serverId + ":" + t.name());
            row.setServerId(serverId);
            row.setName(t.name());
            row.setDescription(t.description());
            row.setInputSchema(toJson(t.inputSchema()));
            String preserved = existingRisk.get(t.name());
            if (preserved != null) {
                row.setRisk(preserved);
            } else {
                row.setRisk(t.risk() == null || t.risk().isBlank() ? "WRITE" : t.risk());
            }
            return row;
        }).toList();
        repo.replaceTools(serverId, rows);
    }

    public Optional<Tool> findTool(String serverId, String toolName) {
        McpToolDO row = repo.findTool(serverId, toolName);
        if (row == null) return Optional.empty();
        return Optional.of(new Tool(row.getName(), row.getDescription(), fromMap(row.getInputSchema()),
                row.getRisk() == null || row.getRisk().isBlank() ? "WRITE" : row.getRisk()));
    }

    public Tool updateToolRisk(String serverId, String toolName, String risk) {
        McpToolDO row = repo.findTool(serverId, toolName);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "mcp tool 不存在");
        }
        if (!repo.updateToolRisk(serverId, toolName, risk)) {
            throw new BizException(ErrorCode.NOT_FOUND, "mcp tool 不存在");
        }
        return new Tool(row.getName(), row.getDescription(), fromMap(row.getInputSchema()), risk);
    }

    public List<Tool> tools(String serverId) {
        return repo.listTools(serverId).stream()
                .map(r -> new Tool(r.getName(), r.getDescription(), fromMap(r.getInputSchema()),
                        r.getRisk() == null || r.getRisk().isBlank() ? "WRITE" : r.getRisk()))
                .toList();
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
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
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
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }

    public record McpServer(String id, String name, String baseUrl, String headerSecretRef, String status) {}

    /** {@code risk} 对应 mcp_tool.risk（V114），缺省 WRITE。 */
    public record Tool(String name, String description, Map<String, Object> inputSchema, String risk) {
        public Tool(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, "WRITE");
        }
    }

}

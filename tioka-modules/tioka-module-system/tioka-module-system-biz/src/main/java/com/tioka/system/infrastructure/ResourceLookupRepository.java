package com.tioka.system.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.infrastructure.mapper.ResourceLookupMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ResourceLookupRepository {
    private final ResourceLookupMapper mapper;

    public ResourceLookupRepository(ResourceLookupMapper mapper) {
        this.mapper = mapper;
    }

    public record ResourceRow(String id, String workspaceId, String ownerId, String visibility) {}

    public ResourceRow find(String resourceType, String resourceId) {
        Map<String, Object> row = switch (resourceType) {
            case "agent" -> mapper.findAgent(resourceId);
            case "kb" -> mapper.findKb(resourceId);
            case "flow" -> mapper.findFlow(resourceId);
            case "mcp" -> mapper.findMcp(resourceId);
            case "skill" -> mapper.findSkill(resourceId);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "未知资源类型: " + resourceType);
        };
        if (row == null || row.isEmpty()) {
            return null;
        }
        return new ResourceRow(
                first(row, "id"),
                first(row, "workspaceId", "workspace_id"),
                first(row, "ownerId", "owner_id"),
                first(row, "visibility"));
    }

    private static String first(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            if (row.containsKey(k) && row.get(k) != null) {
                return String.valueOf(row.get(k));
            }
        }
        // PostgreSQL 驱动偶发返回小写键
        for (Map.Entry<String, Object> e : row.entrySet()) {
            for (String k : keys) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && e.getValue() != null) {
                    return String.valueOf(e.getValue());
                }
            }
        }
        return null;
    }

    public List<String> listIdsInWorkspace(String resourceType, String workspaceId) {
        return switch (resourceType) {
            case "agent" -> mapper.listAgentIds(workspaceId);
            case "kb" -> mapper.listKbIds(workspaceId);
            case "flow" -> mapper.listFlowIds(workspaceId);
            case "mcp" -> mapper.listMcpIds(workspaceId);
            case "skill" -> mapper.listSkillIds(workspaceId);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "未知资源类型: " + resourceType);
        };
    }

}

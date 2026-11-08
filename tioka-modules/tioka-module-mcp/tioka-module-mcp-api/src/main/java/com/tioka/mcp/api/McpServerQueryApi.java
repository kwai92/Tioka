package com.tioka.mcp.api;

import java.util.List;
import java.util.Optional;

/** MCP Server / 工具查询（AST-02 发布校验；ENG-06 目录）。 */
public interface McpServerQueryApi {

    /**
     * 对 mcp_server 行加 {@code FOR SHARE}；不存在则 empty。
     * {@code circuitOpen=true} 表示熔断中（不可用）。
     */
    Optional<ServerRef> lockServer(String serverId);

    /** 工具名是否在 mcp_tool 中（同事务可读到锁内数据）。 */
    boolean hasTool(String serverId, String toolName);

    /** 目录用轻量列表（ENG-06）。 */
    default List<ServerSummary> listSummaries() {
        return List.of();
    }

    record ServerRef(String id, String status, boolean circuitOpen) {}

    record ServerSummary(String id, String name) {}
}

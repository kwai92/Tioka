package com.tioka.runtime.api.context;

import java.util.Map;

/**
 * CTX-01：段提供者渲染入参（ADR-048 第 1 节四区）。
 *
 * <p>由 {@code AgentLoop} 在每一步组装上下文时构造；{@code turnId} 用于 D 区
 * 「本轮尾部按 Turn 冻结」——同一 Turn 的多步之间注入内容必须完全一致，
 * 否则前缀哈希漂移会击穿提示缓存。
 *
 * @param query          本轮用户问题（自动注入检索用；无问题时为空串）
 * @param attributes     调用方附加槽位（数据标签、授予、模式等），只读
 */
public record ContextRenderContext(String turnId, String threadId, String workspaceId,
                                   String agentVersionId, String query,
                                   Map<String, Object> attributes) {
    public ContextRenderContext {
        if (turnId == null) turnId = "";
        if (threadId == null) threadId = "";
        if (workspaceId == null) workspaceId = "";
        if (agentVersionId == null) agentVersionId = "";
        if (query == null) query = "";
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ContextRenderContext of(String turnId, String threadId, String workspaceId,
                                          String agentVersionId, String query) {
        return new ContextRenderContext(turnId, threadId, workspaceId, agentVersionId, query, Map.of());
    }
}

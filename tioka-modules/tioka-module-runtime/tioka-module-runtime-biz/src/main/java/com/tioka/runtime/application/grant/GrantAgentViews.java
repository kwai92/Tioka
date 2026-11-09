package com.tioka.runtime.application.grant;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.label.DataLabel;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.application.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 快照 → GrantService 投影（MAS-01）。KernelRuntimeService（根授予）与
 * DelegateTool（收窄）共用同一口径，保证根授予工具集与模型可见一致。
 */
public final class GrantAgentViews {
    private GrantAgentViews() {}

    public static GrantAgentView fromSnapshot(AgentSnapshotApi.AgentSnapshot snap, ToolRegistry registry) {
        AgentSpec spec = snap.spec();
        Map<String, Object> cfg = agentToolConfigOf(spec);
        Set<String> toolNames = new LinkedHashSet<>();
        for (var d : registry.listAll(snap.versionId(), cfg)) {
            toolNames.add(d.name());
        }
        Map<String, Set<String>> resources = new LinkedHashMap<>();
        if (spec.knowledge() != null && spec.knowledge().kbIds() != null && !spec.knowledge().kbIds().isEmpty()) {
            resources.put("kb", Set.copyOf(spec.knowledge().kbIds()));
        }
        if (spec.tools() != null && spec.tools().mcp() != null && !spec.tools().mcp().isEmpty()) {
            Set<String> serverIds = new LinkedHashSet<>();
            for (AgentSpec.ToolSet.McpBinding b : spec.tools().mcp()) {
                serverIds.add(b.serverId());
            }
            if (!serverIds.isEmpty()) {
                resources.put("mcp", serverIds);
            }
        }
        Set<String> skills = new LinkedHashSet<>();
        for (AgentSpec.SkillRef s : spec.skills()) {
            if (s.skillVersionId() != null) {
                skills.add(s.skillVersionId());
            }
        }
        // TRU-02：动作类别来自类型化 Charter（agent-api Charter）；无授权书 → 空集（M3-01 L37 语义）
        Set<String> classes = Set.of();
        try {
            com.tioka.agent.api.spec.Charter charter =
                    com.tioka.agent.api.spec.Charter.parse(spec.charter());
            if (charter != null && !charter.actionClasses().isEmpty()) {
                classes = new LinkedHashSet<>(charter.actionClasses().stream()
                        .map(com.tioka.agent.api.spec.Charter.ActionClass::code)
                        .filter(c -> c != null && !c.isBlank())
                        .toList());
            }
        } catch (IllegalArgumentException ignored) {
            // charter JSON 非法按无授权书处理（发布校验已拦截）
        }
        DataLabel clearance = parseLabel(spec.dataClearance());
        Budget budget = spec.limits() != null && spec.limits().maxTokensPerTurn() != null
                ? new Budget(java.math.BigDecimal.valueOf(spec.limits().maxTokensPerTurn()), null, null)
                : Budget.empty();
        return new GrantAgentView(snap.versionId(), snap.agentId(), toolNames, classes, skills,
                resources, clearance,
                spec.sandbox() == null || spec.sandbox().networkAllowlist() == null
                        ? Set.of() : Set.copyOf(spec.sandbox().networkAllowlist()),
                budget);
    }

    public static DataLabel parseLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return DataLabel.PUBLIC;
        }
        try {
            return DataLabel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DataLabel.PUBLIC;
        }
    }

    /** CON-01：McpBinding → agentToolConfig（与 TurnRunner 同口径）。 */
    public static Map<String, Object> agentToolConfigOf(AgentSpec spec) {
        if (spec == null || spec.tools() == null || spec.tools().mcp() == null || spec.tools().mcp().isEmpty()) {
            return Map.of("mcp", List.of());
        }
        List<Map<String, Object>> bindings = new java.util.ArrayList<>();
        for (AgentSpec.ToolSet.McpBinding b : spec.tools().mcp()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("serverId", b.serverId());
            g.put("allowlist", b.allowlist() == null ? List.of() : b.allowlist());
            bindings.add(g);
        }
        return Map.of("mcp", bindings);
    }
}

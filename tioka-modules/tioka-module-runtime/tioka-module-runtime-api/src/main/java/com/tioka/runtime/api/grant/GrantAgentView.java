package com.tioka.runtime.api.grant;

import com.tioka.framework.label.DataLabel;

import java.util.Map;
import java.util.Set;

/**
 * GrantService 用的 Agent 快照投影（避免 runtime-api 依赖 agent-api）。
 */
public record GrantAgentView(
        String versionId,
        String agentId,
        Set<String> tools,
        Set<String> actionClasses,
        Set<String> skillVersionIds,
        Map<String, Set<String>> resources,
        DataLabel dataClearance,
        Set<String> egressHosts,
        Budget limitsBudget
) {}

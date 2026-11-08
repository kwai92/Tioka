package com.tioka.agent.api.spec;

import java.util.List;

/**
 * AgentSpec {@code context} 段（ADR-048）。可空；null = 平台默认。
 */
public record ContextSettings(
        Integer budgetTokens,
        Integer cleanupThresholdPercent,
        Integer keepRecentTurns,
        List<String> neverCleanupTools,
        String summaryHint,
        String childPassMode,
        Integer childReturnTokenLimit
) {
    public ContextSettings {
        neverCleanupTools = neverCleanupTools == null ? List.of() : List.copyOf(neverCleanupTools);
    }
}

package com.tioka.agent.api.spec;

/**
 * AgentSpec {@code memory} 段（ADR-050）。可空；null = 平台默认。
 */
public record MemorySettings(
        String readScope,
        String writeMode,
        Boolean defaultAgentAffinity
) {}

package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AgentSpec {@code execution} 段（ADR-046）。可空；null = REACT + NATIVE 现状。
 */
public record ExecutionSettings(
        String strategy,
        JsonNode strategyParams,
        String executor,
        Boolean allowRuntimeOverride
) {}

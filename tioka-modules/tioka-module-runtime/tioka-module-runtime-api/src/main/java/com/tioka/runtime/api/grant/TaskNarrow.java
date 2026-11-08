package com.tioka.runtime.api.grant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 委派收窄参数（ADR-039 / MAS-01）。
 */
public record TaskNarrow(
        Set<String> tools,
        Map<String, Set<String>> resources,
        Map<String, List<String>> scope,
        Budget budget
) {
    public TaskNarrow {
        tools = tools == null ? Set.of() : Set.copyOf(tools);
        resources = resources == null ? Map.of() : Map.copyOf(resources);
        scope = scope == null ? Map.of() : Map.copyOf(scope);
    }

    public Optional<Budget> budgetOpt() {
        return Optional.ofNullable(budget);
    }
}

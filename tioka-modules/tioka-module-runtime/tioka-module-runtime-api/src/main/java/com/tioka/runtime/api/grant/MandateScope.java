package com.tioka.runtime.api.grant;

import java.util.List;
import java.util.Map;

/**
 * 编排 mandate 的任务范围（ADR-039）。
 */
public record MandateScope(Map<String, List<String>> scope) {
    public MandateScope {
        scope = scope == null ? Map.of() : Map.copyOf(scope);
    }

    public static MandateScope empty() {
        return new MandateScope(Map.of());
    }
}

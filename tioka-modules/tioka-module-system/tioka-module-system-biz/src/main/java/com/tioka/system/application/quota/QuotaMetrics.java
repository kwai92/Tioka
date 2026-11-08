package com.tioka.system.application.quota;

import java.util.Set;

/** GOV-03 指标名与作用域。 */
public final class QuotaMetrics {
    public static final String TOKENS_MONTH = "tokens_month";
    public static final String CONCURRENT_TURNS = "concurrent_turns";
    public static final String TOOL_RPM = "tool_rpm";

    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_AGENT = "agent";
    public static final String SCOPE_TOOL = "tool";

    public static final Set<String> ALL_METRICS = Set.of(TOKENS_MONTH, CONCURRENT_TURNS, TOOL_RPM);
    public static final Set<String> ALL_SCOPES = Set.of(SCOPE_WORKSPACE, SCOPE_USER, SCOPE_AGENT, SCOPE_TOOL);

    private QuotaMetrics() {}
}

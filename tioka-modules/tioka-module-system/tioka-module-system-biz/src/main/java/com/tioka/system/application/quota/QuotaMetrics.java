package com.tioka.system.application.quota;

import java.util.Set;

/** GOV-03 指标名与作用域；MH-09 追加供给侧作用对象与月度成本指标（ADR-041 第 3 节）。 */
public final class QuotaMetrics {
    public static final String TOKENS_MONTH = "tokens_month";
    public static final String CONCURRENT_TURNS = "concurrent_turns";
    public static final String TOOL_RPM = "tool_rpm";
    /** 供给侧月度成本（微美分）；metric 允许集见 V218。 */
    public static final String COST_MONTH = "cost_month";

    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_AGENT = "agent";
    public static final String SCOPE_TOOL = "tool";
    /** 供给侧作用对象：供应商（ADR-041 第 3 节，月度 token / 成本）。 */
    public static final String SCOPE_PROVIDER = "provider";
    /** 供给侧作用对象：模型别名（ADR-041 第 3 节，月度成本）。 */
    public static final String SCOPE_ALIAS = "alias";

    public static final Set<String> ALL_METRICS = Set.of(TOKENS_MONTH, CONCURRENT_TURNS, TOOL_RPM, COST_MONTH);
    public static final Set<String> ALL_SCOPES = Set.of(SCOPE_WORKSPACE, SCOPE_USER, SCOPE_AGENT, SCOPE_TOOL,
            SCOPE_PROVIDER, SCOPE_ALIAS);
    /** 供给侧作用对象（读写配额时不认识的作用对象按「不限」处理，约束 F13）。 */
    public static final Set<String> SUPPLY_SCOPES = Set.of(SCOPE_PROVIDER, SCOPE_ALIAS);

    private QuotaMetrics() {}
}

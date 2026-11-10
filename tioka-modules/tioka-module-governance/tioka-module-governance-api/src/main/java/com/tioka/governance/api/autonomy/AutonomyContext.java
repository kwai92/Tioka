package com.tioka.governance.api.autonomy;

import com.tioka.governance.api.policy.PolicyDecision;

/**
 * {@link AutonomyGate#decide} 输入（ADR-034 判定表）。
 *
 * <p>偏差（登记见 M3-C4 设计笔记）：相对 ADR-034 L127-139 扩展实现判定表实际需要的字段——
 * {@code agentId}/{@code agentVersionId}/{@code actionClass}（审计与抽检取数）、
 * {@code tainted}/{@code injectionSensitive}（ADR-034 修订：TAINTED Turn 的
 * injectionSensitive 工具上限 L2，M3 输入恒为 false，字段先行）；{@code reversibility}
 * 为字符串枚举（{@code REVERSIBLE}/{@code COMPENSABLE}/{@code NONE}/{@code IRREVERSIBLE}），
 * 替代布尔可逆性——L2 行要求 ∈ {REVERSIBLE, COMPENSABLE}，L3 行要求 ≠ IRREVERSIBLE，
 * 单一布尔无法同时表达两行。
 */
public record AutonomyContext(
        PolicyDecision cedar,
        String origin,
        int level,
        String reversibility,
        boolean estimated,
        String risk,
        boolean inObservationPeriod,
        String toolPolicy,
        String agentId,
        String agentVersionId,
        String actionClass,
        boolean tainted,
        boolean injectionSensitive
) {}

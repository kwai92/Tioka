package com.tioka.governance.application.autonomy;

import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.policy.PolicyDecision;
import java.util.Locale;

/**
 * 自治等级判定表（ADR-034 L32-49 / TRU-09，管线第 10 步）。自上而下命中即止：
 *
 * <ol>
 *   <li>Cedar = DENY → DENIED</li>
 *   <li>origin = shadow，或等级 = L0 → SHADOW_ONLY</li>
 *   <li>toolPolicy 收紧：deny → DENIED；approve → REQUIRE_APPROVAL（auto 不能越过等级，见下行）</li>
 *   <li>Cedar = ESCALATE → REQUIRE_APPROVAL</li>
 *   <li>TAINTED Turn 的 injectionSensitive 工具：等级按 min(level, L2)（ADR-034 修订 2026-11-07）</li>
 *   <li>观察期且等级 ≥ L3 → 按 L2 判定</li>
 *   <li>L1 → REQUIRE_APPROVAL</li>
 *   <li>L2：可逆（REVERSIBLE/COMPENSABLE）且 !estimated 且 risk=WRITE → AUTO_COMMIT，否则 REQUIRE_APPROVAL</li>
 *   <li>L3：可逆性 ≠ IRREVERSIBLE 且 !estimated → AUTO_COMMIT，否则 REQUIRE_APPROVAL</li>
 *   <li>L4：!estimated → AUTO_COMMIT，否则 REQUIRE_APPROVAL</li>
 * </ol>
 *
 * <p>纯函数：无依赖，等级与收紧配置由调用方（管线第 10 步）装配进 {@link AutonomyContext}。
 * {@code estimated = true} 不能自动提交到 L3 以上（M3-05 §7 / ADR-033）；L2 行的
 * {@code !estimated} 为判定表原文。
 */
public class DefaultAutonomyGate implements AutonomyGate {

    public static final int OBSERVATION_PERIOD_CAP = 3;

    @Override
    public AutonomyDecision decide(AutonomyContext ctx) {
        PolicyDecision cedar = ctx.cedar() == null ? PolicyDecision.ALLOW : ctx.cedar();
        // 1. Cedar DENY
        if (cedar == PolicyDecision.DENY) {
            return AutonomyDecision.DENIED;
        }
        // 2. 影子来源 / L0
        if ("shadow".equalsIgnoreCase(ctx.origin() == null ? "" : ctx.origin()) || ctx.level() <= 0) {
            return AutonomyDecision.SHADOW_ONLY;
        }
        // 3. toolPolicies 只能收紧（approve/deny 照办；auto 不越过等级 → 落到下方判定）
        String toolPolicy = ctx.toolPolicy() == null ? "" : ctx.toolPolicy().trim().toLowerCase();
        if ("deny".equals(toolPolicy)) {
            return AutonomyDecision.DENIED;
        }
        if ("approve".equals(toolPolicy)) {
            return AutonomyDecision.REQUIRE_APPROVAL;
        }
        // 4. Cedar ESCALATE
        if (cedar == PolicyDecision.ESCALATE) {
            return AutonomyDecision.REQUIRE_APPROVAL;
        }
        // 5. TAINTED Turn 的 injectionSensitive 工具上限 L2
        int level = ctx.level();
        if (ctx.tainted() && ctx.injectionSensitive() && level > 2) {
            level = 2;
        }
        // 6. 观察期：≥L3 按 L2
        boolean asL2 = ctx.inObservationPeriod() && level >= OBSERVATION_PERIOD_CAP;
        if (asL2) {
            level = 2;
        }
        // 7〜10. 等级行
        return decideByLevel(level, ctx);
    }

    private AutonomyDecision decideByLevel(int level, AutonomyContext ctx) {
        return switch (level) {
            case 1 -> AutonomyDecision.REQUIRE_APPROVAL;
            case 2 -> compensable(ctx) && !ctx.estimated() && isWrite(ctx.risk())
                    ? AutonomyDecision.AUTO_COMMIT : AutonomyDecision.REQUIRE_APPROVAL;
            case 3 -> !isIrreversible(ctx) && !ctx.estimated()
                    ? AutonomyDecision.AUTO_COMMIT : AutonomyDecision.REQUIRE_APPROVAL;
            default -> ctx.level() >= 4 && !ctx.estimated()
                    ? AutonomyDecision.AUTO_COMMIT : AutonomyDecision.REQUIRE_APPROVAL;
        };
    }

    /** L2 行：可逆性 ∈ {REVERSIBLE, COMPENSABLE}。 */
    private static boolean compensable(AutonomyContext ctx) {
        String rev = ctx.reversibility() == null ? "" : ctx.reversibility().trim().toUpperCase(Locale.ROOT);
        return "REVERSIBLE".equals(rev) || "COMPENSABLE".equals(rev);
    }

    /** L3 行：可逆性 ≠ IRREVERSIBLE。 */
    private static boolean isIrreversible(AutonomyContext ctx) {
        String rev = ctx.reversibility() == null ? "" : ctx.reversibility().trim().toUpperCase(Locale.ROOT);
        return "IRREVERSIBLE".equals(rev);
    }

    private static boolean isWrite(String risk) {
        return "WRITE".equalsIgnoreCase(risk == null ? "" : risk.trim());
    }
}

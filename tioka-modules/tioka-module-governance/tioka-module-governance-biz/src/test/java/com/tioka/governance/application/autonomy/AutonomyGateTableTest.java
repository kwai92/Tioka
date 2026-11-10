package com.tioka.governance.application.autonomy;

import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TRU-09 判定表逐行覆盖（ADR-034 L32-49 + M3-05 §7 补充规则）。表驱动，自上而下命中即止。
 */
class AutonomyGateTableTest {

    private final AutonomyGate gate = new DefaultAutonomyGate();

    /** 一行判定表用例：命中条件 → 期望结果。 */
    private record Row(String name, AutonomyContext ctx, AutonomyDecision expected) {}

    private static AutonomyContext ctx(PolicyDecision cedar, String origin, int level,
                                       String reversibility, boolean estimated, String risk,
                                       boolean observation, String toolPolicy) {
        return new AutonomyContext(cedar, origin, level, reversibility, estimated, risk,
                observation, toolPolicy, "agt_1", "av_1", "order.update", false, false);
    }

    private static final List<Row> TABLE = buildTable();

    private static List<Row> buildTable() {
        List<Row> rows = new ArrayList<>();
        // 1. Cedar = DENY → DENIED
        rows.add(new Row("cedarDeny", ctx(PolicyDecision.DENY, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.DENIED));
        // 2. origin = shadow → SHADOW_ONLY
        rows.add(new Row("originShadow", ctx(PolicyDecision.ALLOW, "shadow", 4,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.SHADOW_ONLY));
        // 3. 等级 = L0 → SHADOW_ONLY
        rows.add(new Row("levelL0", ctx(PolicyDecision.ALLOW, "chat", 0,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.SHADOW_ONLY));
        // 4. toolPolicy deny → DENIED（收紧，L4 也不豁免）
        rows.add(new Row("toolPolicyDeny", ctx(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, "deny"), AutonomyDecision.DENIED));
        // 5. toolPolicy approve → REQUIRE_APPROVAL（收紧）
        rows.add(new Row("toolPolicyApprove", ctx(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, "approve"), AutonomyDecision.REQUIRE_APPROVAL));
        // 6. toolPolicy auto 不越过等级（L1 仍审批）
        rows.add(new Row("toolPolicyAutoNoBypass", ctx(PolicyDecision.ALLOW, "chat", 1,
                "REVERSIBLE", false, "WRITE", false, "auto"), AutonomyDecision.REQUIRE_APPROVAL));
        // 7. Cedar = ESCALATE → REQUIRE_APPROVAL
        rows.add(new Row("cedarEscalate", ctx(PolicyDecision.ESCALATE, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 8. 观察期且 L4 → 按 L2：可逆非估算 WRITE 自动提交
        rows.add(new Row("observationL4Auto", ctx(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", true, null), AutonomyDecision.AUTO_COMMIT));
        // 9. 观察期且 L4 → 按 L2：不可逆转审批
        rows.add(new Row("observationL4Irreversible", ctx(PolicyDecision.ALLOW, "chat", 4,
                "IRREVERSIBLE", false, "WRITE", true, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 10. 观察期不影响 L2（本身按 L2）
        rows.add(new Row("observationL2Unchanged", ctx(PolicyDecision.ALLOW, "chat", 2,
                "REVERSIBLE", false, "WRITE", true, null), AutonomyDecision.AUTO_COMMIT));
        // 11. L1 → REQUIRE_APPROVAL
        rows.add(new Row("l1", ctx(PolicyDecision.ALLOW, "chat", 1,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 12. L2 可逆 + 非估算 + WRITE → AUTO_COMMIT
        rows.add(new Row("l2Auto", ctx(PolicyDecision.ALLOW, "chat", 2,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.AUTO_COMMIT));
        // 13. L2 可冲销（COMPENSABLE）→ AUTO_COMMIT
        rows.add(new Row("l2Compensable", ctx(PolicyDecision.ALLOW, "chat", 2,
                "COMPENSABLE", false, "WRITE", false, null), AutonomyDecision.AUTO_COMMIT));
        // 14. L2 不可逆 → REQUIRE_APPROVAL
        rows.add(new Row("l2Irreversible", ctx(PolicyDecision.ALLOW, "chat", 2,
                "IRREVERSIBLE", false, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 15. L2 estimated → REQUIRE_APPROVAL
        rows.add(new Row("l2Estimated", ctx(PolicyDecision.ALLOW, "chat", 2,
                "REVERSIBLE", true, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 16. L2 非 WRITE 风险 → REQUIRE_APPROVAL
        rows.add(new Row("l2NonWrite", ctx(PolicyDecision.ALLOW, "chat", 2,
                "REVERSIBLE", false, "READ", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 17. L3 可逆 → AUTO_COMMIT
        rows.add(new Row("l3Auto", ctx(PolicyDecision.ALLOW, "chat", 3,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.AUTO_COMMIT));
        // 18. L3 NONE（未声明可逆性）≠ IRREVERSIBLE → AUTO_COMMIT
        rows.add(new Row("l3None", ctx(PolicyDecision.ALLOW, "chat", 3,
                "NONE", false, "WRITE", false, null), AutonomyDecision.AUTO_COMMIT));
        // 19. L3 不可逆 → REQUIRE_APPROVAL
        rows.add(new Row("l3Irreversible", ctx(PolicyDecision.ALLOW, "chat", 3,
                "IRREVERSIBLE", false, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 20. L3 estimated → 不能自动提交（M3-05 §7）
        rows.add(new Row("l3Estimated", ctx(PolicyDecision.ALLOW, "chat", 3,
                "REVERSIBLE", true, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 21. L4 → AUTO_COMMIT
        rows.add(new Row("l4Auto", ctx(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, null), AutonomyDecision.AUTO_COMMIT));
        // 22. L4 estimated → 不能自动提交到 L3 以上
        rows.add(new Row("l4Estimated", ctx(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", true, "WRITE", false, null), AutonomyDecision.REQUIRE_APPROVAL));
        // 23. TAINTED Turn 的 injectionSensitive 工具上限 L2：L4 按 L2 → 可逆 WRITE 自动提交
        rows.add(new Row("taintedCapL2Auto", new AutonomyContext(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, null, "agt_1", "av_1", "order.update",
                true, true), AutonomyDecision.AUTO_COMMIT));
        // 24. TAINTED + injectionSensitive + L4 + 不可逆 → 按 L2 转审批
        rows.add(new Row("taintedCapL2Approve", new AutonomyContext(PolicyDecision.ALLOW, "chat", 4,
                "IRREVERSIBLE", false, "WRITE", false, null, "agt_1", "av_1", "order.update",
                true, true), AutonomyDecision.REQUIRE_APPROVAL));
        // 25. TAINTED 但工具不敏感：L4 不受影响
        rows.add(new Row("taintedNonSensitive", new AutonomyContext(PolicyDecision.ALLOW, "chat", 4,
                "REVERSIBLE", false, "WRITE", false, null, "agt_1", "av_1", "order.update",
                true, false), AutonomyDecision.AUTO_COMMIT));
        return rows;
    }

    @Test
    void decisionTableRowByRow() {
        for (Row row : TABLE) {
            AutonomyDecision actual = gate.decide(row.ctx());
            assertEquals(row.expected(), actual, "判定表行 " + row.name());
        }
    }

    @Test
    void tableCoversEveryFrozenRow() {
        // 判定表行数 ≥ 10（ADR-034 冻结表 + 补充规则全覆盖）
        org.junit.jupiter.api.Assertions.assertTrue(TABLE.size() >= 10,
                "表驱动用例数 " + TABLE.size());
        Function<Row, String> name = Row::name;
        org.junit.jupiter.api.Assertions.assertTrue(TABLE.stream().map(name)
                        .anyMatch(n -> n.startsWith("cedarDeny"))
                        && TABLE.stream().map(name).anyMatch(n -> n.startsWith("l4Auto")),
                "首行（Cedar DENY）与末行（L4）都在覆盖内");
    }
}

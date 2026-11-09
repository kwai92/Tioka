package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.PolicyEvalException;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-07 两遍求值器离线表驱动（M3-05 §1.3，cedar-java 4.11.0 本机原生库）：
 * DENY 组优先、ESCALATE 组次之、否则 ALLOW；无 schema 求值（动作组为 Action 层级父实体，
 * {@code action in Tioka::Action::"{类别}"}，ADR-032 ActionClass 写法偏差登记批次文档）。
 */
class CedarEvaluatorTest {

    private static final PolicyEvaluator EVALUATOR = new PolicyEvaluator();
    private static final long TIMEOUT_MS = 5000;

    /** 授权书编译产物同款动作组/边界写法。 */
    private static final String AGV_COND =
            "(context.agentVersion == \"av1\" || context.chain.contains(\"av1\"))";

    private static ResolvedPolicy forbid(String id, String effect, String cond) {
        String text = "@id(\"%s\")\n@effect(\"%s\")\nforbid(principal, action in Tioka::Action::\"pay\", resource)\nwhen { %s };"
                .formatted(id, effect, cond);
        return ResolvedPolicy.forbid(id, text, PolicyDecision.valueOf(effect), PolicyMode.ENFORCE);
    }

    private static ResolvedPolicy esc(String id, String cond) {
        return forbid(id, "ESCALATE", cond);
    }

    private static ResolvedPolicy deny(String id, String cond) {
        return forbid(id, "DENY", cond);
    }

    /** context 缺省：actionClass=pay、agentVersion=av1、chain=[av1]。 */
    private static PolicyEvalRequest req(Map<String, Object> resourceAttrs,
                                         Map<String, Object> contextOverrides) {
        Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("workspaceId", "ws1");
        ctx.put("agentVersion", "av1");
        ctx.put("actionClass", "pay");
        ctx.put("chain", List.of("av1"));
        ctx.put("dailyAmountMicros", 0L);
        ctx.put("dailyRecords", 0);
        if (contextOverrides != null) {
            ctx.putAll(contextOverrides);
        }
        return new PolicyEvalRequest("user:1", "mcp.pay.run", "tool", "mcp.pay.run",
                resourceAttrs, ctx);
    }

    /** 表驱动行：策略组 + resource 属性 + context 覆盖 + 期望裁决。 */
    private record Row(String name, List<ResolvedPolicy> policies,
                       Map<String, Object> resourceAttrs, Map<String, Object> contextOverrides,
                       PolicyDecision expected) {}

    private static final ResolvedPolicy AMOUNT_CAP = esc("charter:av1:pay:escalate-amount",
            AGV_COND + " && resource.amountMicros > 10000");
    private static final ResolvedPolicy DAILY_AMOUNT = esc("charter:av1:pay:escalate-daily-amount",
            AGV_COND + " && context.dailyAmountMicros > 50000");
    private static final ResolvedPolicy DAILY_RECORDS = esc("charter:av1:pay:escalate-daily-records",
            AGV_COND + " && context.dailyRecords > 100");
    private static final ResolvedPolicy PER_RUN_RECORDS = esc("charter:av1:pay:escalate-per-run-records",
            AGV_COND + " && resource.records > 10");
    private static final ResolvedPolicy SCOPE = deny("charter:av1:pay:scope",
            AGV_COND + " && !([\"C\", \"D\"].contains(resource.attrs.customerLevel))");
    private static final ResolvedPolicy FORBIDDEN_TOOL = deny("charter:av1:pay:forbidden-tool",
            AGV_COND + " && action in [Tioka::Action::\"mcp.pay.run\"]");

    private static final List<Row> ROWS = List.of(
            new Row("空策略集 → ALLOW", List.of(), Map.of("amountMicros", 20000L), null, PolicyDecision.ALLOW),
            new Row("单次金额超限 → ESCALATE", List.of(AMOUNT_CAP), Map.of("amountMicros", 20000L), null, PolicyDecision.ESCALATE),
            new Row("单次金额等于上限 → ALLOW（严格大于）", List.of(AMOUNT_CAP), Map.of("amountMicros", 10000L), null, PolicyDecision.ALLOW),
            new Row("单次金额低于上限 → ALLOW", List.of(AMOUNT_CAP), Map.of("amountMicros", 9999L), null, PolicyDecision.ALLOW),
            new Row("版本与委派链均不匹配 → ALLOW", List.of(AMOUNT_CAP), Map.of("amountMicros", 20000L),
                    Map.of("agentVersion", "av2", "chain", List.of("av2")), PolicyDecision.ALLOW),
            new Row("版本经委派链匹配 → ESCALATE", List.of(AMOUNT_CAP), Map.of("amountMicros", 20000L),
                    Map.of("agentVersion", "av2"), PolicyDecision.ESCALATE),
            new Row("未归类动作（无 actionClass）→ ALLOW", List.of(AMOUNT_CAP), Map.of("amountMicros", 20000L),
                    Map.of("actionClass", ""), PolicyDecision.ALLOW),
            new Row("其他类别 → ALLOW", List.of(AMOUNT_CAP), Map.of("amountMicros", 20000L),
                    Map.of("actionClass", "refund"), PolicyDecision.ALLOW),
            new Row("当日金额超限 → ESCALATE", List.of(DAILY_AMOUNT), Map.of("amountMicros", 1L),
                    Map.of("dailyAmountMicros", 60000L), PolicyDecision.ESCALATE),
            new Row("当日金额等于上限 → ALLOW", List.of(DAILY_AMOUNT), Map.of("amountMicros", 1L),
                    Map.of("dailyAmountMicros", 50000L), PolicyDecision.ALLOW),
            new Row("当日记录数超限 → ESCALATE", List.of(DAILY_RECORDS), Map.of("amountMicros", 1L),
                    Map.of("dailyRecords", 101), PolicyDecision.ESCALATE),
            new Row("单 Run 记录数超限 → ESCALATE", List.of(PER_RUN_RECORDS),
                    Map.of("amountMicros", 1L, "records", 11), null, PolicyDecision.ESCALATE),
            new Row("单 Run 记录数等于上限 → ALLOW", List.of(PER_RUN_RECORDS),
                    Map.of("amountMicros", 1L, "records", 10), null, PolicyDecision.ALLOW),
            new Row("scope 范围外 → DENY", List.of(SCOPE), Map.of("amountMicros", 1L, "customerLevel", "A"), null, PolicyDecision.DENY),
            new Row("scope 范围内 C → ALLOW", List.of(SCOPE), Map.of("amountMicros", 1L, "customerLevel", "C"), null, PolicyDecision.ALLOW),
            new Row("scope 范围内 D → ALLOW", List.of(SCOPE), Map.of("amountMicros", 1L, "customerLevel", "D"), null, PolicyDecision.ALLOW),
            new Row("scope 属性缺失 → ALLOW（策略级错误按未命中处理）", List.of(SCOPE), Map.of("amountMicros", 1L), null, PolicyDecision.ALLOW),
            new Row("禁区工具命中 → DENY", List.of(FORBIDDEN_TOOL), Map.of("amountMicros", 1L), null, PolicyDecision.DENY),
            new Row("禁区不含当前动作 → ALLOW", List.of(deny("f2", AGV_COND
                    + " && action in [Tioka::Action::\"mcp.other.tool\"]")), Map.of("amountMicros", 1L), null, PolicyDecision.ALLOW),
            new Row("DENY 与 ESCALATE 同时命中 → DENY 优先", List.of(AMOUNT_CAP, SCOPE),
                    Map.of("amountMicros", 20000L, "customerLevel", "A"), null, PolicyDecision.DENY),
            new Row("仅 ESCALATE 命中 → ESCALATE", List.of(SCOPE, AMOUNT_CAP),
                    Map.of("amountMicros", 20000L, "customerLevel", "C"), null, PolicyDecision.ESCALATE),
            new Row("非 forbid 策略不参与收紧", List.of(new ResolvedPolicy("weird",
                    "permit(principal, action, resource);", false, PolicyDecision.ALLOW, PolicyMode.DRYRUN)),
                    Map.of("amountMicros", 20000L), null, PolicyDecision.ALLOW),
            new Row("上限为 0 → 任意金额 ESCALATE", List.of(esc("z", AGV_COND + " && resource.amountMicros > 0")),
                    Map.of("amountMicros", 1L), null, PolicyDecision.ESCALATE),
            new Row("多条 ESCALATE 任一命中 → ESCALATE", List.of(AMOUNT_CAP, DAILY_AMOUNT),
                    Map.of("amountMicros", 1L), Map.of("dailyAmountMicros", 60000L), PolicyDecision.ESCALATE));

    @Test
    void tableDriven() throws PolicyEvalException {
        for (Row row : ROWS) {
            PolicyEvalResult r = EVALUATOR.evaluate(req(row.resourceAttrs(), row.contextOverrides()),
                    row.policies(), 3, TIMEOUT_MS);
            assertEquals(row.expected(), r.decision(), row.name());
            assertEquals("3", r.policySetVersion(), row.name());
            assertEquals(false, r.evalError(), row.name());
        }
    }

    @Test
    void escalateHitsReportPolicyIdModeEffect() throws PolicyEvalException {
        PolicyEvalResult r = EVALUATOR.evaluate(req(Map.of("amountMicros", 20000L), null),
                List.of(AMOUNT_CAP), 7, TIMEOUT_MS);
        assertEquals(1, r.hits().size());
        assertEquals("charter:av1:pay:escalate-amount", r.hits().get(0).policyId());
        assertEquals(PolicyMode.ENFORCE, r.hits().get(0).mode());
        assertEquals(PolicyDecision.ESCALATE, r.hits().get(0).effect());
    }

    @Test
    void denyHitsReportAllMatchedPolicies() throws PolicyEvalException {
        ResolvedPolicy scope2 = deny("scope2", AGV_COND + " && resource.amountMicros > 1");
        PolicyEvalResult r = EVALUATOR.evaluate(req(Map.of("amountMicros", 20000L, "customerLevel", "A"), null),
                List.of(SCOPE, scope2), 1, TIMEOUT_MS);
        assertEquals(PolicyDecision.DENY, r.decision());
        assertEquals(2, r.hits().size());
        assertTrue(r.hits().stream().anyMatch(h -> "charter:av1:pay:scope".equals(h.policyId())));
        assertTrue(r.hits().stream().anyMatch(h -> "scope2".equals(h.policyId())));
    }

    @Test
    void parseErrorRaisesPolicyEvalException() {
        ResolvedPolicy broken = ResolvedPolicy.forbid("broken", "forbid(principal", PolicyDecision.DENY, PolicyMode.ENFORCE);
        PolicyEvalException e = assertThrows(PolicyEvalException.class, () ->
                EVALUATOR.evaluate(req(Map.of("amountMicros", 1L), null), List.of(broken), 1, TIMEOUT_MS));
        assertTrue(e.getMessage().contains("failed to parse policy"), e.getMessage());
    }

    @Test
    void emptyPolicySetAllowsWithoutErrors() throws PolicyEvalException {
        PolicyEvalResult r = EVALUATOR.evaluate(req(Map.of("amountMicros", 1L), null), List.of(), 0, TIMEOUT_MS);
        assertEquals(PolicyDecision.ALLOW, r.decision());
        assertTrue(r.hits().isEmpty());
    }
}

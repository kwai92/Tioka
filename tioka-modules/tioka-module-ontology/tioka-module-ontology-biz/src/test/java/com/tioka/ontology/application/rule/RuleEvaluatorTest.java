package com.tioka.ontology.application.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.rule.RuleResult;
import com.tioka.ontology.api.rule.RuleSpec;
import com.tioka.ontology.application.pack.CelFunction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONT-04 表驱动（M3-03 §9）：适用 / 不适用 × 已知 / 未知 / 空白 / 不适用 × 满足 / 不满足，
 * 加超时、表达式错误、待复核传递。≥24 行。
 */
class RuleEvaluatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 标准模型：feeItems[RENT@S1]（sched.baseAmount 已知 / abate.freeMonths 可变状态）。 */
    private static JsonNode model(String freeMonthsValue, String freeMonthsStatus) throws Exception {
        String cell = freeMonthsStatus == null
                ? String.valueOf(Integer.parseInt(freeMonthsValue))
                : "{\"value\": %s, \"status\": \"%s\"}".formatted(freeMonthsValue, freeMonthsStatus);
        return M.readTree("""
                {
                  "feeItems": [
                    {
                      "x-tioka-identity": "RENT@S1",
                      "sched": {"baseAmount": {"value": 10000, "status": "explicit",
                                               "sources": [{"unitId": "u1", "quote": "租金 10000"}]}},
                      "abate": {"freeMonths": %s}
                    }
                  ],
                  "statements": [
                    {"type": "OBLIGATION", "action": {"value": "repair", "status": "explicit"}}
                  ]
                }
                """.formatted(cell));
    }

    private static JsonNode knownModel() throws Exception {
        return model("2", "explicit");
    }

    private static RuleSpec spec(String applies, List<String> requires, String check, String onBlank) {
        return new RuleSpec("lease.D.017", applies, requires, check, onBlank, List.of("minFreeMonths"));
    }

    private static final String CHECK_KNOWN = "m.feeItems['RENT@S1'].abate.freeMonths >= cfg.minFreeMonths";

    private static final String CHECK_TRUE = "m.feeItems['RENT@S1'].sched.baseAmount >= 1";
    private static final String CHECK_FALSE = "m.feeItems['RENT@S1'].sched.baseAmount < 1";

    /** 表驱动矩阵。 */
    private static List<Case> cases() throws Exception {
        List<Case> out = new java.util.ArrayList<>();
        // 1-2 applies 为假 → 不适用（check 本应满足 / 本应报错）
        out.add(new Case("applies假_满假设", spec("has(m.feeItems.MISS)", List.of(), CHECK_TRUE, null),
                knownModel(), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_APPLICABLE, false, false));
        out.add(new Case("applies假_错假设", spec("has(m.feeItems.MISS)", List.of(), "m.feeItems['RENT@S1'] >= 1", null),
                knownModel(), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_APPLICABLE, false, false));
        // 3-4 requires 未知 → 缺失
        out.add(new Case("requires未知_缺失", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, null),
                model("0", "unknown"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.MISSING, false, false));
        out.add(new Case("requires路径不存在_缺失", spec(null, List.of("m.feeItems['RENT@S1'].deposit"), CHECK_KNOWN, null),
                knownModel(), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.MISSING, false, false));
        // 5-6 requires 不适用 → 不适用
        out.add(new Case("requires不适用", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, null),
                model("2", "not_applicable"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_APPLICABLE, false, false));
        out.add(new Case("requires不适用_优先于空白", spec(null,
                List.of("m.feeItems['RENT@S1'].abate", "m.feeItems['RENT@S1'].sched.baseAmount"), CHECK_KNOWN, null),
                model("0", "not_applicable"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_APPLICABLE, false, false));
        // 7-8 requires 空白 → onBlank（缺省 NOT_SATISFIED / 显式 SATISFIED）
        out.add(new Case("空白_缺省onBlank", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, null),
                model("0", "blank"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_SATISFIED, false, false));
        out.add(new Case("空白_显式SATISFIED", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, "SATISFIED"),
                model("0", "blank"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.SATISFIED, false, false));
        // 9-14 已知值 check 满足 / 不满足（不同阈值 / 表达式形态）
        out.add(new Case("已知_满足", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, null),
                model("2", "explicit"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.SATISFIED, false, false));
        out.add(new Case("已知_不满足", spec(null, List.of("m.feeItems['RENT@S1'].abate"), CHECK_KNOWN, null),
                model("0", "explicit"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_SATISFIED, false, false));
        out.add(new Case("已知_满足_点选择器", spec(null, List.of(),
                "m.feeItems['RENT@S1'].sched.baseAmount == 10000", null),
                knownModel(), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.SATISFIED, false, false));
        out.add(new Case("已知_不满足_点选择器", spec(null, List.of(),
                "m.feeItems['RENT@S1'].sched.baseAmount == 1", null),
                knownModel(), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_SATISFIED, false, false));
        out.add(new Case("无applies无requires_常量真", spec(null, List.of(), "true", null),
                knownModel(), Map.of(), RuleResult.Conclusion.SATISFIED, false, false));
        out.add(new Case("无applies无requires_常量假", spec(null, List.of(), "false", null),
                knownModel(), Map.of(), RuleResult.Conclusion.NOT_SATISFIED, false, false));
        // 15-16 check 访问未知值 → 缺失（CEL 运行期分派失败 → error 路径）
        out.add(new Case("check访问未知_缺失", spec(null, List.of(), CHECK_KNOWN, null),
                model("0", "unknown"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.MISSING, false, true));
        out.add(new Case("check访问空白_缺失", spec(null, List.of(), CHECK_KNOWN, null),
                model("0", "blank"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.MISSING, false, true));
        // 17-18 待复核按已知值参与计算 + needsReview（requires 侧 / check 侧）
        out.add(new Case("待复核requires_满足+needsReview", spec(null, List.of("m.feeItems['RENT@S1'].abate"),
                CHECK_KNOWN, null),
                model("2", "review"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.SATISFIED, true, false));
        out.add(new Case("待复核check_满足+needsReview", spec(null, List.of("m.feeItems['RENT@S1'].abate"),
                "m.feeItems['RENT@S1'].sched.baseAmount >= cfg.minFreeMonths", null),
                model("2", "review"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.SATISFIED, true, false));
        // 19-20 表达式错误 / 编译错误 → 缺失 + error
        out.add(new Case("check类型错误_缺失", spec(null, List.of(), "m.feeItems['RENT@S1'] >= 1", null),
                knownModel(), Map.of(), RuleResult.Conclusion.MISSING, false, true));
        out.add(new Case("check编译错误_缺失", spec(null, List.of(), "m.feeItems. =", null),
                knownModel(), Map.of(), RuleResult.Conclusion.MISSING, false, true));
        // 21-22 平台内置函数 sum / months
        out.add(new Case("内置sum_满足", spec(null, List.of(),
                "sum([10000, 2000]) == 12000.0 && m.feeItems['RENT@S1'].sched.baseAmount > 0", null),
                knownModel(), Map.of(), RuleResult.Conclusion.SATISFIED, false, false));
        out.add(new Case("内置months_满足", spec(null, List.of(),
                "months({'years': 1, 'months': 3}) == 15 && m.feeItems['RENT@S1'].sched.baseAmount > 0", null),
                knownModel(), Map.of(), RuleResult.Conclusion.SATISFIED, false, false));
        // 23-24 内置 isKnown / status
        out.add(new Case("内置isKnown_未知为假", spec(null, List.of(),
                "isKnown(m.feeItems['RENT@S1'].abate.freeMonths) == false", null),
                model("0", "unknown"), Map.of(), RuleResult.Conclusion.SATISFIED, false, false));
        out.add(new Case("内置isKnown_已知为真", spec(null, List.of(),
                "isKnown(m.feeItems['RENT@S1'].sched.baseAmount) == true", null),
                knownModel(), Map.of(), RuleResult.Conclusion.SATISFIED, false, false));
        // 25 annualRate + 检查参数记 configVersion
        out.add(new Case("内置annualRate", spec(null, List.of(),
                "annualRate(m.feeItems['RENT@S1'].sched.segments) == 0.05 && cfg.minRate == 0.05", null),
                M.readTree("""
                        {
                          "feeItems": [
                            {"x-tioka-identity": "RENT@S1",
                             "sched": {"baseAmount": {"value": 1, "status": "explicit"},
                                       "segments": [{"rate": 0.05, "years": 2}]}
                            }
                          ]
                        }
                        """), Map.of("minRate", 0.05), RuleResult.Conclusion.SATISFIED, false, false));
        // 26 待复核不满足也带 needsReview
        out.add(new Case("待复核_不满足+needsReview", spec(null, List.of("m.feeItems['RENT@S1'].abate"),
                CHECK_KNOWN, null),
                model("0", "review"), Map.of("minFreeMonths", 1L), RuleResult.Conclusion.NOT_SATISFIED, true, false));
        return out;
    }

    private record Case(String name, RuleSpec spec, JsonNode model, Map<String, Object> cfg,
                        RuleResult.Conclusion expected, boolean needsReview, boolean expectError) {
    }

    @Test
    void tableDriven() throws Exception {
        RuleEvaluator evaluator = new RuleEvaluator();
        int satisfied = 0;
        int notSatisfied = 0;
        int missing = 0;
        int notApplicable = 0;
        for (Case c : cases()) {
            RuleResult r = evaluator.evaluate(c.spec(), c.model(), c.cfg(), "cfgv-1");
            assertEquals(c.expected(), r.conclusion(), c.name() + " error=" + r.error() + " touched=" + r.touched());
            assertEquals(c.needsReview(), r.needsReview(), c.name() + " needsReview");
            if (c.expectError()) {
                assertNotNull(r.error(), c.name() + " error 信息");
            } else {
                assertEquals(null, r.error(), c.name() + " 无 error");
            }
            assertFalse(r.touched().isEmpty() && c.expected() == RuleResult.Conclusion.NOT_APPLICABLE
                            && !c.name().startsWith("applies"),
                    c.name() + " touched 不为空（applies 假时也保留 applies 内路径）");
            switch (r.conclusion()) {
                case SATISFIED -> satisfied++;
                case NOT_SATISFIED -> notSatisfied++;
                case MISSING -> missing++;
                case NOT_APPLICABLE -> notApplicable++;
            }
            if (!c.spec().params().isEmpty()) {
                assertEquals("cfgv-1", r.params().get(0).configVersion(), c.name() + " configVersion");
                // 参数值按实际参数名从 cfg 取（自洽：缺省该参数时记 null）
                assertEquals(c.cfg().get(r.params().get(0).name()),
                        r.params().get(0).value(), c.name() + " 参数值");
            }
        }
        assertEquals(26, cases().size());
        assertTrue(satisfied >= 9, "satisfied=" + satisfied);
        assertTrue(notSatisfied >= 4, "notSatisfied=" + notSatisfied);
        assertTrue(missing >= 6, "missing=" + missing);
        assertTrue(notApplicable >= 3, "notApplicable=" + notApplicable);
    }

    @Test
    void touchedContainsRequiresAndCheckPathsWithValueAndStatus() throws Exception {
        RuleEvaluator evaluator = new RuleEvaluator();
        RuleSpec rule = spec(null,
                List.of("m.feeItems['RENT@S1'].abate", "m.feeItems['RENT@S1'].sched.baseAmount"),
                CHECK_KNOWN, null);
        RuleResult r = evaluator.evaluate(rule, model("2", "explicit"), Map.of("minFreeMonths", 1L), "cfgv-1");
        assertEquals(RuleResult.Conclusion.SATISFIED, r.conclusion());
        List<String> paths = r.touched().stream().map(RuleResult.Touched::path).toList();
        assertTrue(paths.contains("m.feeItems['RENT@S1'].abate"), () -> "touched=" + paths);
        assertTrue(paths.contains("m.feeItems['RENT@S1'].abate.freeMonths"), () -> "touched=" + paths);
        RuleResult.Touched leaf = r.touched().stream()
                .filter(t -> t.path().equals("m.feeItems['RENT@S1'].abate.freeMonths"))
                .findFirst().orElseThrow();
        assertEquals(2L, leaf.value());
        assertEquals("KNOWN", leaf.status());
        // sources 来自 ValueCell
        RuleResult.Touched amount = r.touched().stream()
                .filter(t -> t.path().equals("m.feeItems['RENT@S1'].sched.baseAmount"))
                .findFirst().orElseThrow();
        assertEquals("KNOWN", amount.status());
        assertTrue(!amount.sources().isEmpty());
        assertEquals("u1", amount.sources().get(0).get("unitId"));
    }

    @Test
    void timeoutReturnsErrorAndMissing() throws Exception {
        Function<Object, Object> slow = arg -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        };
        RuleEvaluator evaluator = new RuleEvaluator(
                List.of(new CelFunction("slowCheck", "测试慢函数", slow)), 50L);
        RuleSpec rule = spec(null, List.of(),
                "slowCheck(m.feeItems['RENT@S1']) >= 0", null);
        RuleResult r = evaluator.evaluate(rule, knownModel(), Map.of(), null);
        assertEquals(RuleResult.Conclusion.MISSING, r.conclusion());
        assertNotNull(r.error());
        assertTrue(r.error().contains("cel_timeout"), () -> "error=" + r.error());
    }

    @Test
    void packFunctionMemberCallSatisfiedAndNotSatisfied() throws Exception {
        // 领域包注册 freeMonths()（成员形态，接收者为 abate cell map）
        RuleEvaluator evaluator = new RuleEvaluator(List.of(new CelFunction("freeMonths", "免租月数",
                arg -> {
                    if (arg instanceof Map<?, ?> abate) {
                        Object v = abate.get("freeMonths");
                        if (v instanceof Map<?, ?> cell) {
                            return ((Number) cell.get("value")).longValue();
                        }
                        return v instanceof Number n ? n.longValue() : 0L;
                    }
                    return 0L;
                })));
        RuleSpec rule = spec(null, List.of("m.feeItems['RENT@S1'].abate"),
                "m.feeItems['RENT@S1'].abate.freeMonths() >= cfg.minFreeMonths", null);
        assertEquals(RuleResult.Conclusion.SATISFIED,
                evaluator.evaluate(rule, model("2", "explicit"), Map.of("minFreeMonths", 1L), "v1").conclusion());
        assertEquals(RuleResult.Conclusion.NOT_SATISFIED,
                evaluator.evaluate(rule, model("0", "explicit"), Map.of("minFreeMonths", 1L), "v1").conclusion());
    }

    @Test
    void compileCachePerRuleIdAndExpression() throws Exception {
        RuleEvaluator evaluator = new RuleEvaluator();
        RuleSpec rule = spec(null, List.of(), CHECK_TRUE, null);
        evaluator.evaluate(rule, knownModel(), Map.of(), null);
        // 同规则再求值走缓存；不同表达式同规则也命中
        RuleResult r = evaluator.evaluate(rule, knownModel(), Map.of(), null);
        assertEquals(RuleResult.Conclusion.SATISFIED, r.conclusion());
        evaluator.evict("lease.D.017");
        assertEquals(RuleResult.Conclusion.SATISFIED,
                evaluator.evaluate(rule, knownModel(), Map.of(), null).conclusion());
    }
}

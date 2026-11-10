package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.CharterPolicyCompilerApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRU-02 授权书编译器离线测试（M3-05 §3.1 / ADR-032）：六类规则产物、@id 口径、
 * 委派链条件、动作组写法（Action 层级），以及编译产物 → Cedar 求值联动。
 */
class CharterCompilerTest {

    private static final String WS = "ws1";
    private static final String AGV = "av1";

    private final PolicyStore store = mock(PolicyStore.class);
    private final PolicyChangeNotifier notifier = mock(PolicyChangeNotifier.class);
    private final DefaultCharterPolicyCompiler compiler =
            new DefaultCharterPolicyCompiler(store, notifier, "u1");

    /** 全_limits 动作类别：金额/记录数/当日/范围全配。 */
    private static CharterPolicyCompilerApi.ActionClassSpec fullPayClass() {
        return new CharterPolicyCompilerApi.ActionClassSpec(
                "pay", List.of("mcp.pay.*"),
                10000L, 50000L, 10, 100,
                Map.of("customerLevel", List.of("C", "D")),
                true, false);
    }

    private static CharterPolicyCompilerApi.CompilationCommand cmd(
            List<CharterPolicyCompilerApi.ActionClassSpec> classes, List<String> forbiddenTools) {
        return new CharterPolicyCompilerApi.CompilationCommand(WS, AGV, classes, forbiddenTools);
    }

    private static PolicyStore.CedarPolicySpec specOf(List<PolicyStore.CedarPolicySpec> specs, String rule) {
        return specs.stream().filter(s -> s.policyId().endsWith(":" + rule)).findFirst().orElseThrow();
    }

    @Test
    void fullLimitsCompileToSixRules() {
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(
                cmd(List.of(fullPayClass()), List.of("mcp.drop.table")));
        assertEquals(6, specs.size(), "禁区 + 金额 + 单Run记录 + 当日金额 + 当日记录 + scope");
        assertEquals("charter:" + AGV + ":pay:escalate-amount", specOf(specs, "escalate-amount").policyId());
        assertEquals("charter:" + AGV + ":pay:escalate-daily-amount", specOf(specs, "escalate-daily-amount").policyId());
        assertEquals("charter:" + AGV + ":pay:escalate-per-run-records", specOf(specs, "escalate-per-run-records").policyId());
        assertEquals("charter:" + AGV + ":pay:escalate-daily-records", specOf(specs, "escalate-daily-records").policyId());
        assertEquals("charter:" + AGV + ":pay:scope", specOf(specs, "scope").policyId());
        assertEquals("charter:" + AGV + ":pay:forbidden-tool", specOf(specs, "forbidden-tool").policyId());
    }

    @Test
    void effectsMapToRuleKinds() {
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(
                cmd(List.of(fullPayClass()), List.of("mcp.drop.table")));
        assertEquals(PolicyDecision.ESCALATE, specOf(specs, "escalate-amount").effect());
        assertEquals(PolicyDecision.ESCALATE, specOf(specs, "escalate-daily-records").effect());
        assertEquals(PolicyDecision.DENY, specOf(specs, "scope").effect());
        assertEquals(PolicyDecision.DENY, specOf(specs, "forbidden-tool").effect());
    }

    @Test
    void escalateAmountTextShape() {
        PolicyStore.CedarPolicySpec s = specOf(
                compiler.compileToSpecs(cmd(List.of(fullPayClass()), List.of())), "escalate-amount");
        assertTrue(s.cedarText().contains("@effect(\"ESCALATE\")"), s.cedarText());
        assertTrue(s.cedarText().contains("action in Tioka::Action::\"pay\""),
                "动作组为 Action 层级父实体（ADR-032 ActionClass 写法偏差登记）");
        assertTrue(s.cedarText().contains("resource.amountMicros > 10000"), s.cedarText());
        assertTrue(s.cedarText().contains("(context.agentVersion == \"" + AGV
                        + "\" || context.chain.contains(\"" + AGV + "\"))"),
                "条件含委派链口径（M3-01 L146）");
    }

    @Test
    void scopeTextShape() {
        PolicyStore.CedarPolicySpec s = specOf(
                compiler.compileToSpecs(cmd(List.of(fullPayClass()), List.of())), "scope");
        // Cedar 列表成员用 contains（in 仅用于实体层级成员判定，实测登记）
        assertTrue(s.cedarText().contains("([\"C\", \"D\"].contains(resource.attrs.customerLevel))"),
                s.cedarText());
    }

    @Test
    void forbiddenToolTextShape() {
        PolicyStore.CedarPolicySpec s = specOf(
                compiler.compileToSpecs(cmd(List.of(fullPayClass()), List.of("mcp.drop.table"))), "forbidden-tool");
        // 实体列表字面量须完整类型 Tioka::Action::"..."（短写 Action::"..." 静默不匹配，C-2c 修正）
        assertTrue(s.cedarText().contains("action in [Tioka::Action::\"mcp.drop.table\"]"), s.cedarText());
    }

    @Test
    void forbiddenToolsGeneratePerClass() {
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(cmd(
                List.of(fullPayClass(),
                        new CharterPolicyCompilerApi.ActionClassSpec(
                                "refund", List.of("biz.refund.*"), null, null, null, null, null, false, false)),
                List.of("mcp.drop.table")));
        assertEquals(2, specs.stream().filter(s -> s.policyId().endsWith(":forbidden-tool")).count(),
                "禁区按类别各生成一条（供委派链祖先生效）");
        assertTrue(specs.stream().anyMatch(s -> s.policyId().equals("charter:" + AGV + ":refund:forbidden-tool")));
    }

    @Test
    void noLimitsMeansOnlyForbiddenTools() {
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(cmd(
                List.of(new CharterPolicyCompilerApi.ActionClassSpec(
                        "pay", List.of("mcp.pay.*"), null, null, null, null, null, true, false)),
                List.of()));
        assertTrue(specs.isEmpty(), "无禁区无限额 → 空策略组");
    }

    @Test
    void nullClassesMeansClearGroup() {
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(cmd(null, List.of("mcp.drop.table")));
        assertTrue(specs.isEmpty(), "无动作类别 = 版本回退语义：清空该版本策略组");
    }

    @Test
    void compileDelegatesToStoreAndNotifies() {
        compiler.compile(cmd(List.of(fullPayClass()), List.of("mcp.drop.table")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicyStore.CedarPolicySpec>> specs =
                ArgumentCaptor.forClass((Class) List.class);
        verify(store).replaceCharterPolicies(eq(WS), eq(AGV), specs.capture(), eq("u1"));
        assertEquals(6, specs.getValue().size());
        verify(notifier).notifyChanged(eq(WS), anyInt(), eq("charter:" + AGV),
                eq(PolicyMode.ENFORCE.name()));
    }

    @Test
    void deactivateDelegates() {
        when(store.currentSetVersion(WS)).thenReturn(9);
        compiler.deactivate(WS, AGV);
        verify(store).deactivateCharterPolicies(WS, AGV);
        verify(notifier).notifyChanged(WS, 9, "charter:" + AGV, PolicyMode.ENFORCE.name());
    }

    @Test
    void compiledOutputEvaluatesToEscalate() throws Exception {
        // 编译产物直接喂给真实 Cedar 求值器（编译 → 求值联动）
        PolicyStore.CedarPolicySpec s = specOf(
                compiler.compileToSpecs(cmd(List.of(fullPayClass()), List.of())), "escalate-amount");
        PolicyEvaluator evaluator = new PolicyEvaluator();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentVersion", AGV);
        ctx.put("chain", List.of(AGV));
        ctx.put("actionClass", "pay");
        PolicyEvalResult r = evaluator.evaluate(
                new PolicyEvalRequest("user:1", "mcp.pay.run", "tool", "mcp.pay.run",
                        Map.of("amountMicros", 20000L), ctx),
                List.of(new ResolvedPolicy(s.policyId(), s.cedarText(), true, s.effect(), PolicyMode.ENFORCE)),
                1, 5000);
        assertEquals(PolicyDecision.ESCALATE, r.decision());
        assertEquals("charter:" + AGV + ":pay:escalate-amount", r.hits().get(0).policyId());
    }

    @Test
    void compiledOutputBelowLimitAllows() throws Exception {
        PolicyStore.CedarPolicySpec s = specOf(
                compiler.compileToSpecs(cmd(List.of(fullPayClass()), List.of())), "escalate-amount");
        PolicyEvaluator evaluator = new PolicyEvaluator();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("agentVersion", AGV);
        ctx.put("chain", List.of(AGV));
        ctx.put("actionClass", "pay");
        PolicyEvalResult r = evaluator.evaluate(
                new PolicyEvalRequest("user:1", "mcp.pay.run", "tool", "mcp.pay.run",
                        Map.of("amountMicros", 10000L), ctx),
                List.of(new ResolvedPolicy(s.policyId(), s.cedarText(), true, s.effect(), PolicyMode.ENFORCE)),
                1, 5000);
        assertEquals(PolicyDecision.ALLOW, r.decision());
        verify(store, never()).replaceCharterPolicies(anyString(), anyString(), anyList(), anyString());
    }
}

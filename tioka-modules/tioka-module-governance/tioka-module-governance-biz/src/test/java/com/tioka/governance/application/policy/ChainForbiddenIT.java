package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.CharterPolicyCompilerApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MAS-02 委派链祖先禁区参与求值（M3-01 L128-146 / L155，离线构造）：
 * 祖先授权书禁区工具 X 编译为 {@code charter:{祖先}:ops:forbidden-tool}（ENFORCE / DENY），
 * 条件含 {@code context.chain.contains("祖先")} → 孙子（跨两 hop）调用 X 仍 DENIED 且
 * 结果带策略 @id（管线第 9 步据此拒绝 40340 并写审计）；chain 不含祖先 → 放行（对照组）。
 *
 * <p>全真实链路：DefaultCharterPolicyCompiler 编译产物 → PolicyCache（Loader 内存装配，
 * 模拟 C-2b {@code store.loadActiveForEvaluation(workspaceId)} 全量加载语义）→
 * DefaultPolicyService → PolicyEvaluator（Cedar JNI）。无容器、无 Redis、无 DB。
 */
class ChainForbiddenIT {

    private static final String WS = "ws_chain";
    private static final String ANC = "av_anc";
    private static final String CHILD = "av_child";
    private static final String GK = "av_gk";
    private static final String FORBIDDEN_TOOL = "mcp.drop.table";
    private static final String EXPECTED_ID = "charter:" + ANC + ":ops:forbidden-tool";

    private DefaultPolicyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 祖先授权书编译：ops 类别（含禁区工具的 toolPatterns）+ 授权书级禁区 X
        DefaultCharterPolicyCompiler compiler = new DefaultCharterPolicyCompiler(
                mock(PolicyStore.class), mock(PolicyChangeNotifier.class), "u1");
        List<PolicyStore.CedarPolicySpec> specs = compiler.compileToSpecs(
                new CharterPolicyCompilerApi.CompilationCommand(WS, ANC,
                        List.of(new CharterPolicyCompilerApi.ActionClassSpec(
                                "ops", List.of("mcp.ops.*", FORBIDDEN_TOOL),
                                null, null, null, null, null, false, false)),
                        List.of(FORBIDDEN_TOOL)));
        assertEquals(1, specs.size(), "无无限额 → 仅禁区一条");
        PolicyStore.CedarPolicySpec forbid = specs.get(0);
        assertEquals(EXPECTED_ID, forbid.policyId());
        assertEquals(PolicyDecision.DENY, forbid.effect());
        assertTrue(forbid.cedarText().contains("context.chain.contains(\"" + ANC + "\")"),
                "条件含委派链口径（M3-01 L146）");

        // Loader 内存装配：模拟 workspace 全量 ACTIVE 加载（祖先 charter 策略天然参与后代求值）
        List<ResolvedPolicy> resolved = specs.stream()
                .map(s -> new ResolvedPolicy(s.policyId(), s.cedarText(), true,
                        s.effect(), PolicyMode.ENFORCE))
                .toList();
        PolicyCache cache = new PolicyCache(60, new PolicyCache.Loader() {
            @Override public List<ResolvedPolicy> load(String workspaceId) { return resolved; }
            @Override public int version(String workspaceId) { return 1; }
        });
        // Cedar JNI 首次求值含初始化开销，默认 20ms 超时会误触 eval-error→DENY 兜底；IT 放宽
        PolicyProperties props = new PolicyProperties();
        props.setEvalTimeoutMs(5000);
        service = new DefaultPolicyService(mock(PolicyStore.class), cache, new PolicyEvaluator(),
                mock(PolicyChangeNotifier.class), props, null, null);
    }

    private static PolicyEvalRequest req(String agentVersion, List<String> chain, String tool) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("workspaceId", WS);
        ctx.put("agentVersion", agentVersion);
        ctx.put("chain", chain);
        ctx.put("actionClass", "ops");
        ctx.put("origin", "chat");
        return new PolicyEvalRequest("user:1", tool, "tool", tool, Map.of(), ctx);
    }

    @Test
    void grandchildDeniedByAncestorForbid() {
        // 孙子（跨两 hop）：chain = [祖先, 子, 孙子]
        PolicyEvalResult r = service.evaluate(req(GK, List.of(ANC, CHILD, GK), FORBIDDEN_TOOL));
        assertEquals(PolicyDecision.DENY, r.decision(), "祖先禁区对孙子生效");
        assertEquals(EXPECTED_ID, r.hits().get(0).policyId(), "审计可还原：结果带策略 @id");
        assertEquals(PolicyMode.ENFORCE, r.hits().get(0).mode());
    }

    @Test
    void directChildDeniedByAncestorForbid() {
        PolicyEvalResult r = service.evaluate(req(CHILD, List.of(ANC, CHILD), FORBIDDEN_TOOL));
        assertEquals(PolicyDecision.DENY, r.decision(), "祖先禁区对直接子生效");
        assertEquals(EXPECTED_ID, r.hits().get(0).policyId());
    }

    @Test
    void chainWithoutAncestorAllows() {
        // 对照组：chain 不含祖先 → 禁区不生效（M3-01 L146 条件语义）
        PolicyEvalResult r = service.evaluate(req(GK, List.of(GK), FORBIDDEN_TOOL));
        assertEquals(PolicyDecision.ALLOW, r.decision());
        assertEquals(List.of(), r.hits());
    }

    @Test
    void ancestorForbidOnlyHitsForbiddenTool() {
        // 非禁区工具（在类别 toolPatterns 内）不受禁区影响
        PolicyEvalResult r = service.evaluate(req(GK, List.of(ANC, CHILD, GK), "mcp.ops.run"));
        assertEquals(PolicyDecision.ALLOW, r.decision());
        assertEquals(List.of(), r.hits());
    }

    @Test
    void compiledGroupNotifiesCharterSource() {
        // compile（非 compileToSpecs）走 store + 广播：source = charter:{祖先}
        PolicyStore store = mock(PolicyStore.class);
        PolicyChangeNotifier notifier = mock(PolicyChangeNotifier.class);
        when(store.currentSetVersion(WS)).thenReturn(3);
        DefaultCharterPolicyCompiler compiler = new DefaultCharterPolicyCompiler(store, notifier, "u1");
        compiler.compile(new CharterPolicyCompilerApi.CompilationCommand(WS, ANC,
                List.of(new CharterPolicyCompilerApi.ActionClassSpec(
                        "ops", List.of(FORBIDDEN_TOOL), null, null, null, null, null, false, false)),
                List.of(FORBIDDEN_TOOL)));
        verify(store).replaceCharterPolicies(eq(WS), eq(ANC), org.mockito.ArgumentMatchers.anyList(),
                eq("u1"));
        verify(notifier).notifyChanged(eq(WS), anyInt(), eq("charter:" + ANC), anyString());
    }
}

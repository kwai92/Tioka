package com.tioka.runtime.application.grant;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.ChainHop;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.grant.TaskNarrow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAS-01 收窄校验表驱动测试（M3-01 L117：工具、类别、资源、scope、标签、预算、期限、深度
 * 各有「收窄通过」「放大拒绝」两类用例）。
 */
class NarrowingVerifierTest {
    static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    static DelegationGrant parent() {
        return new DelegationGrant("grt_parent", null, "user:1", null,
                List.of(new ChainHop("av_parent", null)),
                Set.of("echo", "kb.search", "biz.erp.writeOff", "flow.run"),
                Set.of("lease.finalize", "crm.update"),
                Set.of("skl_1"),
                Map.of("kb", Set.of("kb_a", "kb_b"), "mcp", Set.of("srv_1")),
                Map.of("contractId", List.of("HT-1", "HT-2")),
                DataLabel.CONFIDENTIAL,
                Set.of("api.example.com"),
                new Budget(BigDecimal.valueOf(10000), null, null),
                NOW.plus(Duration.ofHours(24)),
                GrantStatus.ACTIVE, null);
    }

    static GrantAgentView child(Set<String> tools, Set<String> classes,
                                Map<String, Set<String>> resources, DataLabel label, Budget budget) {
        return new GrantAgentView("av_child", "agv_child", tools, classes, Set.of("skl_1"),
                resources, label, Set.of("api.example.com"), budget);
    }

    static GrantAgentView fullChild() {
        return child(Set.of("echo", "kb.search", "biz.erp.writeOff", "flow.run"),
                Set.of("lease.finalize", "crm.update"),
                Map.of("kb", Set.of("kb_a", "kb_b"), "mcp", Set.of("srv_1")),
                DataLabel.CONFIDENTIAL,
                new Budget(BigDecimal.valueOf(5000), null, null));
    }

    record Case(String name, UnaryOperator<DelegationGrant> parentMutator,
                UnaryOperator<GrantAgentView> childMutator, TaskNarrow narrow,
                boolean expectOk, ErrorCode expectError, String expectFragment) {}

    static List<Case> cases() {
        return List.of(
                // ---- 工具 ----
                new Case("T1 工具收窄通过", p -> p, c -> c,
                        new TaskNarrow(Set.of("echo", "kb.search"), null, null, null), true, null, null),
                new Case("T2 工具放大拒绝：narrow 引入父没有的工具", p -> p, c -> c,
                        new TaskNarrow(Set.of("biz.erp.writeOff", "admin.reset"), null, null, null),
                        false, ErrorCode.GRANT_NOT_NARROWER, "不在父授予内"),
                new Case("T3 工具交集生效：子快照无 writeOff", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), Set.of("echo"), c.actionClasses(),
                                c.skillVersionIds(), c.resources(), c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("T4 子快照工具越父：交集裁剪不放大", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(),
                                Set.of("echo", "kb.search", "biz.erp.writeOff", "flow.run", "admin.reset"),
                                c.actionClasses(), c.skillVersionIds(), c.resources(), c.dataClearance(),
                                c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                // ---- 动作类别 ----
                new Case("T5 类别收窄通过", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(),
                                Set.of("lease.finalize"), c.skillVersionIds(), c.resources(),
                                c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("T6 类别放大拒绝：子声明父没有的类别", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(),
                                Set.of("lease.finalize", "user.delete"), c.skillVersionIds(),
                                c.resources(), c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("T7 类别交集断言：结果 ⊆ 父类别", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(),
                                Set.of("lease.finalize", "crm.update"), c.skillVersionIds(),
                                c.resources(), c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                // ---- 资源 ----
                new Case("R1 资源收窄通过：kb 交集", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                c.skillVersionIds(), Map.of("kb", Set.of("kb_a", "kb_b", "kb_c")),
                                c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, Map.of("kb", Set.of("kb_a")), null, null), true, null, null),
                new Case("R2 资源放大拒绝：narrow 出父没有的 kb", p -> p, c -> c,
                        new TaskNarrow(null, Map.of("kb", Set.of("kb_z")), null, null),
                        false, ErrorCode.GRANT_NOT_NARROWER, "收窄资源不在父授予内"),
                new Case("R3 资源放大拒绝：narrow 出父没有的类别", p -> p, c -> c,
                        new TaskNarrow(null, Map.of("dataset", Set.of("ds_1")), null, null),
                        false, ErrorCode.GRANT_NOT_NARROWER, "收窄资源类别不在父授予内"),
                new Case("R4 子绑定越父：交集裁剪", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                c.skillVersionIds(), Map.of("kb", Set.of("kb_a", "kb_x")),
                                c.dataClearance(), c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                // ---- scope ----
                new Case("S1 scope 同键交集通过", p -> p, c -> c,
                        new TaskNarrow(null, null, Map.of("contractId", List.of("HT-1")), null),
                        true, null, null),
                new Case("S2 scope 交集为空拒绝", p -> p, c -> c,
                        new TaskNarrow(null, null, Map.of("contractId", List.of("HT-9")), null),
                        false, ErrorCode.GRANT_NOT_NARROWER, "scope 交集为空"),
                new Case("S3 scope 新键追加通过", p -> p, c -> c,
                        new TaskNarrow(null, null, Map.of("region", List.of("east")), null),
                        true, null, null),
                new Case("S4 scope 新键越父不校验（追加即约束）", p -> p, c -> c,
                        new TaskNarrow(null, null, Map.of("region", List.of("east", "west")), null),
                        true, null, null),
                // ---- 数据标签 ----
                new Case("L1 标签收窄通过", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                c.skillVersionIds(), c.resources(), DataLabel.INTERNAL,
                                c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("L2 标签取 min：子更高也不放大", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                c.skillVersionIds(), c.resources(), DataLabel.SECRET,
                                c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                // ---- 预算 ----
                new Case("B1 预算收窄通过", p -> p, c -> c,
                        new TaskNarrow(null, null, null, new Budget(BigDecimal.valueOf(4000), null, null)),
                        true, null, null),
                new Case("B2 预算超父剩余拒绝", p -> p, c -> c,
                        new TaskNarrow(null, null, null, new Budget(BigDecimal.valueOf(20000), null, null)),
                        false, ErrorCode.GRANT_NOT_NARROWER, "预算超出父授予剩余额度"),
                new Case("B3 预算缺省 = 父剩余一半", p -> p, c -> c,
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("B4 父已用 token 后预算按剩余校验", p -> NarrowingVerifierTest.usedParent(8000), c -> c,
                        new TaskNarrow(null, null, null, new Budget(BigDecimal.valueOf(3000), null, null)),
                        false, ErrorCode.GRANT_NOT_NARROWER, "预算超出父授予剩余额度"),
                // ---- 期限 ----
                new Case("E1 期限取 min（子 TTL 更短）", p -> p, c -> c,
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("E2 父授予已过期拒绝委派", p -> NarrowingVerifierTest.expiredParent(), c -> c,
                        new TaskNarrow(null, null, null, null),
                        false, ErrorCode.GRANT_INACTIVE, "父授予已失效"),
                new Case("E3 父授予 REAPED 拒绝委派", p -> NarrowingVerifierTest.reapedParent(), c -> c,
                        new TaskNarrow(null, null, null, null),
                        false, ErrorCode.GRANT_INACTIVE, "父授予已失效"),
                // ---- 深度 ----
                new Case("D1 深度未超限通过", p -> p, c -> c,
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("D2 深度超限拒绝（chain=2，maxDepth=2）", p -> NarrowingVerifierTest.deepParent(), c -> c,
                        new TaskNarrow(null, null, null, null),
                        false, ErrorCode.GRANT_NOT_NARROWER, "委派深度超限"),
                // ---- Skill / 出网 ----
                new Case("K1 skill 交集通过", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                Set.of("skl_1", "skl_2"), c.resources(), c.dataClearance(),
                                c.egressHosts(), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null),
                new Case("N1 出网交集 = 父 ∩ 子沙箱", p -> p,
                        c -> new GrantAgentView(c.versionId(), c.agentId(), c.tools(), c.actionClasses(),
                                c.skillVersionIds(), c.resources(), c.dataClearance(),
                                Set.of("api.example.com", "other.example.com"), c.limitsBudget()),
                        new TaskNarrow(null, null, null, null), true, null, null));
    }

    static DelegationGrant usedParent(long used) {
        return parent(); // usedTokens 走独立入参，见 narrow 调用
    }

    static DelegationGrant expiredParent() {
        DelegationGrant p = parent();
        return new DelegationGrant(p.grantId(), p.parentGrantId(), p.rootPrincipalRef(), p.onBehalfOf(),
                p.chain(), p.tools(), p.actionClasses(), p.skillVersionIds(), p.resources(), p.scope(),
                p.maxDataLabel(), p.egressHosts(), p.budget(),
                NOW.minus(Duration.ofHours(1)), GrantStatus.ACTIVE, p.hash());
    }

    static DelegationGrant reapedParent() {
        DelegationGrant p = parent();
        return new DelegationGrant(p.grantId(), p.parentGrantId(), p.rootPrincipalRef(), p.onBehalfOf(),
                p.chain(), p.tools(), p.actionClasses(), p.skillVersionIds(), p.resources(), p.scope(),
                p.maxDataLabel(), p.egressHosts(), p.budget(), p.expiresAt(), GrantStatus.REAPED, p.hash());
    }

    static DelegationGrant deepParent() {
        DelegationGrant p = parent();
        // chain = 3（根 + 两跳）已到 maxDepth=2 上限，再 narrow 必须拒绝
        return new DelegationGrant(p.grantId(), "grt_mid", p.rootPrincipalRef(), p.onBehalfOf(),
                List.of(new ChainHop("av_root", null), new ChainHop("av_mid", null), new ChainHop("av_parent", null)),
                p.tools(), p.actionClasses(), p.skillVersionIds(), p.resources(), p.scope(),
                p.maxDataLabel(), p.egressHosts(), p.budget(), p.expiresAt(), p.status(), p.hash());
    }

    @ParameterizedTest(name = "{index} {0}")
    @MethodSource("cases")
    void verify(Case c) {
        DelegationGrant p = c.parentMutator().apply(parent());
        GrantAgentView child = c.childMutator().apply(fullChild());
        long parentUsed = c.name().startsWith("B4") ? 8000 : 0;
        if (c.expectOk()) {
            DelegationGrant out = NarrowingVerifier.narrow(p, child, c.narrow(),
                    p.actionClasses(), parentUsed, Duration.ofHours(1), 2, NOW);
            assertEquals(p.grantId(), out.parentGrantId());
            assertTrue(out.tools().stream().allMatch(t -> p.tools().contains(t)), "子工具 ⊆ 父");
            assertTrue(out.chain().size() == p.chain().size() + 1);
            assertEquals(DataLabel.min(p.maxDataLabel(), child.dataClearance()), out.maxDataLabel());
            if (p.scope().get("contractId") != null && c.narrow() != null
                    && c.narrow().scope().get("contractId") != null) {
                assertTrue(out.scope().get("contractId").containsAll(
                        new java.util.HashSet<>(c.narrow().scope().get("contractId"))));
            }
        } else {
            BizException ex = assertThrows(BizException.class, () -> NarrowingVerifier.narrow(p, child,
                    c.narrow(), p.actionClasses(), parentUsed, Duration.ofHours(1), 2, NOW));
            assertEquals(c.expectError(), ex.getErrorCode(), c.name());
            if (c.expectFragment() != null) {
                assertTrue(String.valueOf(ex.getMessage()).contains(c.expectFragment()), c.name());
            }
        }
    }

    @Test
    void narrowDefaultBudgetIsHalfOfRemaining() {
        DelegationGrant out = NarrowingVerifier.narrow(parent(), fullChild(), new TaskNarrow(null, null, null, null),
                parent().actionClasses(), 0, Duration.ofHours(1), 2, NOW);
        assertEquals(0, BigDecimal.valueOf(5000).compareTo(out.budget().amountLimit()));
    }

    @Test
    void narrowPreservesOnBehalfOfAndRootPrincipal() {
        DelegationGrant out = NarrowingVerifier.narrow(parent(), fullChild(), new TaskNarrow(null, null, null, null),
                parent().actionClasses(), 0, Duration.ofHours(1), 2, NOW);
        assertEquals("user:1", out.rootPrincipalRef());
        assertEquals(2, out.chain().size());
    }
}

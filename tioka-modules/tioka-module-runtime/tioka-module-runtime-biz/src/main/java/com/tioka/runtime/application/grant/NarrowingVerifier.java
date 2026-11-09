package com.tioka.runtime.application.grant;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.label.DataLabel;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.ChainHop;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.grant.TaskNarrow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 收窄校验纯函数（M3-01 L53-71 / ADR-039 签发规则）：证明子 ⊆ 父，失败抛 GRANT_NOT_NARROWER。
 *
 * <p>无 Spring 依赖；表驱动单元测试覆盖。签发 ID 由本类生成（Ids），
 * 持久化与事务归 {@link DefaultGrantService}。
 */
public final class NarrowingVerifier {
    private NarrowingVerifier() {}

    /**
     * @param parentCharterClasses 父授权书可下放动作类别（MAS-02 delegable）；TRU-02 未落地前由调用方传
     *                             {@code parent.actionClasses()}（登记偏差）
     * @param parentUsedTokens     父授予已消耗 token（GrantBody.usedTokens，预算剩余口径）
     * @param childTtl             子授予期限上限（子快照无 turnTimeout 投影时的近似，登记偏差）
     * @param maxDepth             平台委派深度上限（tioka.runtime.delegate.max-depth，默认 2）
     */
    public static DelegationGrant narrow(DelegationGrant p,
                                         GrantAgentView c,
                                         TaskNarrow n,
                                         Set<String> parentCharterClasses,
                                         long parentUsedTokens,
                                         Duration childTtl,
                                         int maxDepth,
                                         Instant now) {
        if (p == null || c == null) {
            throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "父授予与子快照必填");
        }
        if (p.status() != GrantStatus.ACTIVE || (p.expiresAt() != null && now.isAfter(p.expiresAt()))) {
            throw new BizException(ErrorCode.GRANT_INACTIVE, "父授予已失效，不能继续委派");
        }

        // 工具：父 ∩ 子快照 ∩ narrow；narrow 出现父没有的工具 → 抛错（不静默丢弃）
        Set<String> narrowedTools = n == null || n.tools().isEmpty() ? null : n.tools();
        for (String t : narrowedTools == null ? Set.<String>of() : narrowedTools) {
            if (!p.tools().contains(t)) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "收窄工具不在父授予内: " + t);
            }
        }
        Set<String> tools = intersect(p.tools(), c.tools(), narrowedTools);

        // 动作类别：父 ∩ 父授权书 delegable ∩ 子快照；narrow 不涉及类别
        Set<String> classes = intersect(p.actionClasses(), parentCharterClasses == null ? Set.of() : parentCharterClasses, null);
        classes = intersect(classes, c.actionClasses(), null);

        // 资源：逐类取交集；narrow 出现父没有的资源 → 抛错
        Map<String, Set<String>> resources = intersectResources(p.resources(), c.resources(),
                n == null ? Map.of() : n.resources());

        // scope：同键取交集（空 → 抛错），新键追加
        Map<String, List<String>> scope = andScope(p.scope(), n == null ? Map.of() : n.scope());

        DataLabel max = DataLabel.min(p.maxDataLabel(), c.dataClearance());

        // 预算：b ≤ 父剩余额度；缺省 = 父剩余的一半
        Budget child = budgetOf(p, c, n, parentUsedTokens);

        // 期限：min(父, now + 子 TTL)
        Instant exp = p.expiresAt();
        if (childTtl != null) {
            Instant byTtl = now.plus(childTtl);
            exp = exp == null ? byTtl : (exp.isBefore(byTtl) ? exp : byTtl);
        }

        // 深度：p.chain.size() >= maxDepth + 1 → 委派深度超限
        int chainSize = p.chain() == null ? 1 : p.chain().size();
        if (chainSize >= maxDepth + 1) {
            throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "委派深度超限");
        }
        List<ChainHop> chain = new ArrayList<>(p.chain() == null ? List.of() : p.chain());
        chain.add(new ChainHop(c.versionId(), null));

        // 出网：父 ∩ 子沙箱白名单
        Set<String> egress = intersect(p.egressHosts(), c.egressHosts(), null);
        Set<String> skills = intersect(p.skillVersionIds(), c.skillVersionIds(), null);

        return new DelegationGrant(Ids.newId("grt"), p.grantId(), p.rootPrincipalRef(), p.onBehalfOf(),
                List.copyOf(chain), tools, classes, skills, resources, scope, max, egress,
                child, exp, GrantStatus.ACTIVE, null);
    }

    /** a ∩ b ∩ c；c 为 null 表示该层不裁剪（取 a ∩ b）。 */
    static Set<String> intersect(Set<String> a, Set<String> b, Set<String> c) {
        Set<String> sa = a == null ? Set.of() : a;
        Set<String> sb = b == null ? Set.of() : b;
        Set<String> out = new LinkedHashSet<>();
        for (String v : sa) {
            if (sb.contains(v) && (c == null || c.contains(v))) {
                out.add(v);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * 资源逐类交集（ADR-039 签发规则）。规则：
     * narrow 出现父没有的资源类别或 ID → 抛错；子快照绑定 ∩ 父授予 ∩ narrow。
     */
    static Map<String, Set<String>> intersectResources(Map<String, Set<String>> parent,
                                                       Map<String, Set<String>> child,
                                                       Map<String, Set<String>> narrow) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, Set<String>> np = parent == null ? Map.of() : parent;
        Map<String, Set<String>> nc = child == null ? Map.of() : child;
        Map<String, Set<String>> nn = narrow == null ? Map.of() : narrow;
        for (Map.Entry<String, Set<String>> e : nn.entrySet()) {
            Set<String> allowed = np.get(e.getKey());
            if (allowed == null) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "收窄资源类别不在父授予内: " + e.getKey());
            }
            for (String id : e.getValue()) {
                if (!allowed.contains(id)) {
                    throw new BizException(ErrorCode.GRANT_NOT_NARROWER,
                            "收窄资源不在父授予内: " + e.getKey() + "/" + id);
                }
            }
        }
        for (String kind : np.keySet()) {
            Set<String> merged = intersect(np.get(kind), nc.get(kind), nn.get(kind));
            if (!merged.isEmpty()) {
                out.put(kind, merged);
            }
        }
        return Map.copyOf(out);
    }

    /** scope：同键取交集，交集为空 → 抛错；新键追加。 */
    static Map<String, List<String>> andScope(Map<String, List<String>> parent,
                                              Map<String, List<String>> narrow) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, List<String>> np = parent == null ? Map.of() : parent;
        Map<String, List<String>> nn = narrow == null ? Map.of() : narrow;
        for (Map.Entry<String, List<String>> e : np.entrySet()) {
            List<String> nv = nn.get(e.getKey());
            if (nv == null || nv.isEmpty()) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
                continue;
            }
            List<String> inter = new ArrayList<>();
            for (String v : new TreeSet<>(e.getValue())) {
                if (nv.contains(v)) {
                    inter.add(v);
                }
            }
            if (inter.isEmpty()) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER,
                        "scope 交集为空: " + e.getKey());
            }
            out.put(e.getKey(), List.copyOf(inter));
        }
        for (Map.Entry<String, List<String>> e : nn.entrySet()) {
            if (!out.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                out.put(e.getKey(), List.copyOf(new TreeSet<>(e.getValue())));
            }
        }
        return Map.copyOf(out);
    }

    /**
     * 子预算：n.budget 存在时每个非空字段 ≤ 父剩余额度（amountLimit 按父额度 − 已用）；
     * 缺省 = 父剩余额度的一半（M3-01：fraction(0.5)）。
     */
    static Budget budgetOf(DelegationGrant p, GrantAgentView c, TaskNarrow n, long parentUsedTokens) {
        Budget pb = p.budget() == null ? Budget.empty() : p.budget();
        Budget nb = n == null || n.budgetOpt().isEmpty() ? null : n.budget();
        long parentRemaining = remaining(pb, parentUsedTokens);
        if (nb != null) {
            if (nb.amountLimit() != null && pb.amountLimit() != null
                    && nb.amountLimit().subtract(BigDecimal.valueOf(parentRemaining)).signum() > 0) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "预算超出父授予剩余额度");
            }
            if (nb.recordLimit() != null && pb.recordLimit() != null
                    && nb.recordLimit() > pb.recordLimit()) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "记录数预算超出父授予");
            }
            if (nb.stepLimit() != null && pb.stepLimit() != null
                    && nb.stepLimit() > pb.stepLimit()) {
                throw new BizException(ErrorCode.GRANT_NOT_NARROWER, "步数预算超出父授予");
            }
            return nb;
        }
        // 缺省：父剩余的一半（向下取整）；无额度字段时取子快照限制
        BigDecimal half = pb.amountLimit() == null ? null
                : BigDecimal.valueOf(parentRemaining).divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.DOWN);
        Integer recHalf = pb.recordLimit() == null ? null : Math.max(1, pb.recordLimit() / 2);
        Integer stepHalf = pb.stepLimit() == null ? null : Math.max(1, pb.stepLimit() / 2);
        if (half == null && recHalf == null && stepHalf == null && c.limitsBudget() != null) {
            return c.limitsBudget();
        }
        return new Budget(half, recHalf, stepHalf);
    }

    /** 剩余额度 = amountLimit − usedTokens（amountLimit 为空 = 不限，返回 Long.MAX_VALUE/2 占位）。 */
    public static long remaining(Budget b, long usedTokens) {
        if (b == null || b.amountLimit() == null) {
            return Long.MAX_VALUE / 2;
        }
        long rem = b.amountLimit().longValue() - usedTokens;
        return Math.max(0, rem);
    }
}

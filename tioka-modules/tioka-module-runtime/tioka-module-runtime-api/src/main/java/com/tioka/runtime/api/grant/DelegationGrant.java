package com.tioka.runtime.api.grant;

import com.tioka.framework.label.DataLabel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 委派授予快照（ADR-039 / MAS-01）。字段对齐 ADR；实现在 runtime-biz。
 *
 * <p>C-17 修复：紧凑构造器把集合字段规范化为确定性迭代序（TreeSet/TreeMap）。
 * {@code body_json} 以整串 CAS 做乐观锁（MAS-03 预算回拨），而 Jackson 反序列化
 * {@code Set}/{@code Map} 默认产出 HashSet/LinkedHashMap 等迭代序不确定的集合，
 * 导致「读出 → 修改 → 写回」的序列化结果与库中字符串不同，CAS 永远失败
 * （授予预算并发冲突 → reap 回滚 → 父 Turn 永久 WAITING_CHILD）。
 * 规范化后同一内容的序列化结果唯一，CAS 语义成立；Set/Map 的迭代序本就无业务语义。
 */
public record DelegationGrant(
        String grantId,
        String parentGrantId,
        String rootPrincipalRef,
        String onBehalfOf,
        List<ChainHop> chain,
        Set<String> tools,
        Set<String> actionClasses,
        Set<String> skillVersionIds,
        Map<String, Set<String>> resources,
        Map<String, List<String>> scope,
        DataLabel maxDataLabel,
        Set<String> egressHosts,
        Budget budget,
        Instant expiresAt,
        GrantStatus status,
        String hash
) {
    public DelegationGrant {
        // chain 保留 null：下游以「chain == null ? 1 : size」推导 depth（根授予旧口径），
        // 规范化为空链会把 depth 从 1 改成 0，破坏 PolicyEvalPipelineStep 语义
        if (chain != null) {
            chain = List.copyOf(chain);
        }
        tools = canonical(tools);
        actionClasses = canonical(actionClasses);
        skillVersionIds = canonical(skillVersionIds);
        egressHosts = canonical(egressHosts);
        if (resources == null) {
            resources = Map.of();
        } else {
            Map<String, Set<String>> m = new java.util.TreeMap<>();
            resources.forEach((k, v) -> m.put(k, canonical(v)));
            resources = m;
        }
        if (scope == null) scope = Map.of();
        else scope = new java.util.TreeMap<>(scope);
    }

    private static Set<String> canonical(Set<String> in) {
        if (in == null) {
            return Set.of();
        }
        return new java.util.TreeSet<>(in);
    }
}

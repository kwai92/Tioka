package com.tioka.runtime.api.grant;

import com.tioka.framework.label.DataLabel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 委派授予快照（ADR-039 / MAS-01）。字段对齐 ADR；实现在 runtime-biz。
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
) {}

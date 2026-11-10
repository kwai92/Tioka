package com.tioka.flow.application.publish;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DAT-04 启动前检查（ADR-029 第 47 条）：{@code requiredCapabilities ⊆ 调用方 root grant}，
 * 超授予启动即拒（FlowGrantIT#rejectAtStart）。
 */
public final class FlowCapabilityChecker {

    private FlowCapabilityChecker() {}

    /** @return 缺失能力清单；空 = 放行。 */
    public static List<String> missing(CapabilityCollector.RequiredCapabilities caps, DelegationGrant grant) {
        List<String> missing = new ArrayList<>();
        if (caps == null || caps.isEmpty()) {
            return missing;
        }
        if (grant == null || grant.status() != GrantStatus.ACTIVE) {
            for (String t : caps.tools()) missing.add("tool:" + t);
            for (String k : caps.kb()) missing.add("kb:" + k);
            for (String m : caps.mcp()) missing.add("mcp:" + m);
            for (String a : caps.agents()) missing.add("agent:" + a);
            return missing;
        }
        Set<String> tools = grant.tools() == null ? Set.of() : grant.tools();
        for (String t : caps.tools()) {
            if (!tools.contains(t)) {
                missing.add("tool:" + t);
            }
        }
        Map<String, Set<String>> resources = grant.resources() == null ? Map.of() : grant.resources();
        Set<String> kb = resources.getOrDefault("kb", Set.of());
        for (String k : caps.kb()) {
            if (!kb.contains(k)) {
                missing.add("kb:" + k);
            }
        }
        Set<String> mcp = resources.getOrDefault("mcp", Set.of());
        for (String m : caps.mcp()) {
            if (!mcp.contains(m)) {
                missing.add("mcp:" + m);
            }
        }
        Set<String> agents = resources.getOrDefault("agent", Set.of());
        for (String a : caps.agents()) {
            if (!agents.contains(a)) {
                missing.add("agent:" + a);
            }
        }
        return missing;
    }

    /** 超授予抛 FORBIDDEN（启动即拒）。 */
    public static void assertWithinGrant(CapabilityCollector.RequiredCapabilities caps,
                                         DelegationGrant grant) {
        List<String> missing = missing(caps, grant);
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "Flow 所需能力超出调用方授予: " + String.join(", ", missing));
        }
    }

    /** 节点级资源校验（FlowGrantIT#nodesUseCallerGrant）：kind ∈ {kb, mcp, agent}。 */
    public static boolean resourceGranted(DelegationGrant grant, String kind, String ref) {
        if (ref == null || ref.isBlank()) {
            return true;
        }
        if (grant == null || grant.status() != GrantStatus.ACTIVE) {
            return false;
        }
        Map<String, Set<String>> resources = grant.resources() == null ? Map.of() : grant.resources();
        return resources.getOrDefault(kind, Set.of()).contains(ref)
                || Objects.requireNonNullElse(resources.get("*"), Set.of()).contains(ref);
    }
}

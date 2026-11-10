package com.tioka.flow.application.publish;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flow 授予透传离线测试（DAT-04 / ADR-029 第 47 条）：
 * 启动前 requiredCapabilities ⊆ 调用方 root grant（rejectAtStart）、
 * 节点级资源校验（nodesUseCallerGrant）、能力收集与 JSON 往返。
 * 纯函数 + 内存构造（离线优先惯例）。
 */
class FlowGrantIT {

    private static DelegationGrant grant(GrantStatus status, Set<String> tools,
                                         Map<String, Set<String>> resources) {
        return new DelegationGrant("g_1", null, "user_1", null, List.of(),
                tools, Set.of(), Set.of(), resources, Map.of(),
                DataLabel.INTERNAL, Set.of(),
                null, Instant.now().plusSeconds(3600), status, "h");
    }

    private static CapabilityCollector.RequiredCapabilities caps(List<String> tools,
                                                                 List<String> kb,
                                                                 List<String> mcp,
                                                                 List<String> agents) {
        return new CapabilityCollector.RequiredCapabilities(tools, kb, mcp, agents);
    }

    // ---------- rejectAtStart：超授予启动即拒 ----------
    @Test
    void rejectAtStart() {
        DelegationGrant g = grant(GrantStatus.ACTIVE,
                Set.of("tool_a"),
                Map.of("kb", Set.of("kb_1"), "mcp", Set.of("srv_1"), "agent", Set.of("agt_1")));

        // 完全授予内 → 放行
        FlowCapabilityChecker.assertWithinGrant(
                caps(List.of("tool_a"), List.of("kb_1"), List.of("srv_1"), List.of("agt_1")), g);

        // 工具超授予 → FORBIDDEN + 缺失清单含 tool:tool_b
        BizException e = assertThrows(BizException.class, () -> FlowCapabilityChecker.assertWithinGrant(
                caps(List.of("tool_a", "tool_b"), List.of("kb_1"), List.of(), List.of()), g));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        assertTrue(e.getMessage().contains("tool:tool_b"));

        // 各资源类型逐类缺失
        List<String> missing = FlowCapabilityChecker.missing(
                caps(List.of("tool_b"), List.of("kb_2"), List.of("srv_9"), List.of("agt_9")), g);
        assertEquals(List.of("tool:tool_b", "kb:kb_2", "mcp:srv_9", "agent:agt_9"), missing);

        // grant null / 非 ACTIVE → 全部视为缺失
        assertEquals(4, FlowCapabilityChecker.missing(
                caps(List.of("t"), List.of("k"), List.of("m"), List.of("a")), null).size());
        assertEquals(4, FlowCapabilityChecker.missing(
                caps(List.of("t"), List.of("k"), List.of("m"), List.of("a")),
                grant(GrantStatus.REVOKED, Set.of("t"), Map.of())).size());

        // 空 caps → 恒放行
        assertTrue(FlowCapabilityChecker.missing(caps(List.of(), List.of(), List.of(), List.of()), null).isEmpty());
    }

    // ---------- nodesUseCallerGrant：节点级资源校验 ----------
    @Test
    void nodesUseCallerGrant() {
        DelegationGrant g = grant(GrantStatus.ACTIVE, Set.of(),
                Map.of("kb", Set.of("kb_1"), "mcp", Set.of("srv_1")));

        // 授予内资源可访问
        assertTrue(FlowCapabilityChecker.resourceGranted(g, "kb", "kb_1"));
        assertTrue(FlowCapabilityChecker.resourceGranted(g, "mcp", "srv_1"));

        // 未授予资源拒绝
        assertFalse(FlowCapabilityChecker.resourceGranted(g, "kb", "kb_2"));
        assertFalse(FlowCapabilityChecker.resourceGranted(g, "agent", "agt_1"));

        // "*" 通配资源段
        DelegationGrant wildcard = grant(GrantStatus.ACTIVE, Set.of(),
                Map.of("*", Set.of("kb_9")));
        assertTrue(FlowCapabilityChecker.resourceGranted(wildcard, "kb", "kb_9"));

        // null / 非 ACTIVE grant 一律拒绝
        assertFalse(FlowCapabilityChecker.resourceGranted(null, "kb", "kb_1"));
        assertFalse(FlowCapabilityChecker.resourceGranted(
                grant(GrantStatus.REAPED, Set.of(), Map.of("kb", Set.of("kb_1"))), "kb", "kb_1"));

        // ref 空白 = 不校验（节点未配置资源）
        assertTrue(FlowCapabilityChecker.resourceGranted(g, "kb", null));
        assertTrue(FlowCapabilityChecker.resourceGranted(g, "kb", " "));
    }

    // ---------- CapabilityCollector：节点遍历 + JSON 往返 ----------
    @Test
    void collectCapabilitiesFromGraph() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("type", "Retrieve",
                                "config", Map.of("kbId", "kb_1")),
                        Map.of("type", "MCP",
                                "config", Map.of("serverId", "srv_1")),
                        Map.of("type", "Agent",
                                "config", Map.of("agentId", "agt_1")),
                        Map.of("type", "LLM",
                                "config", Map.of("prompt", "x")),
                        Map.of("type", "Retrieve",
                                "config", Map.of())));

        CapabilityCollector.RequiredCapabilities caps = CapabilityCollector.collect(graph);
        assertEquals(List.of("kb_1"), caps.kb());
        assertEquals(List.of("srv_1"), caps.mcp());
        assertEquals(List.of("agt_1"), caps.agents());
        assertEquals(List.of(), caps.tools());

        // JSON 往返
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = CapabilityCollector.toJson(caps, mapper);
        assertEquals(caps, CapabilityCollector.fromJson(json, mapper));

        // 空图 / 脏 JSON 回落空 caps
        assertTrue(CapabilityCollector.collect(Map.of()).isEmpty());
        assertTrue(CapabilityCollector.fromJson("not-json", mapper).isEmpty());
        assertTrue(CapabilityCollector.fromJson(null, mapper).isEmpty());
    }
}

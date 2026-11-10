package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TRU-02 授权书动作类别收紧离线测试（M3-05 §3.2，OnBehalfOf 端到端版本归 CI）：
 * risk != READ 工具动作类别须 ∈ grant.actionClasses；未归类拒绝；READ 放行；
 * 无授权书（存量链路）/ 快照不可用保持放行兼容。
 */
class CharterEnforceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Principal USER = new Principal("1", "ws1", PrincipalType.USER,
            Set.of(), Set.of("*"), "password");

    /** 工具注册表：pay 写工具（WRITE）、kb 读工具（READ）。 */
    private static ToolRegistry registry() {
        ToolProvider provider = new ToolProvider() {
            @Override public String name() { return "builtin"; }
            @Override public ToolResult invoke(String toolName, Map<String, Object> arguments) {
                return new ToolResult(false, "not invoked");
            }
            @Override public List<ToolDescriptor> listTools(String av) {
                return List.of(
                        ToolDescriptor.builder().name("mcp.pay.run").risk(ToolRisk.WRITE).build(),
                        ToolDescriptor.builder().name("kb.query").risk(ToolRisk.READ).build());
            }
        };
        return new ToolRegistry(List.of(provider));
    }

    /** 授权书：pay 类别（mcp.pay.*，fourEyes）。 */
    private static AgentSpec charterSpec(String charterJson) throws Exception {
        return new AgentSpec(1, null, null, null, null, null, null, null, null, null,
                charterJson == null ? null : MAPPER.readTree(charterJson),
                null, null, null, null, null, null, null);
    }

    private static final String CHARTER_JSON = """
            {"actionClasses":[{"code":"pay","name":"支付","toolPatterns":["mcp.pay.*"],"fourEyes":true}]}
            """;

    private static AgentSnapshotApi.AgentSnapshot snapshot(AgentSpec spec) {
        return new AgentSnapshotApi.AgentSnapshot("av1", "ag1", 1, "ws1", "u1", "hash", spec, null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentSnapshotApi> snapshotProvider(AgentSnapshotApi api) {
        ObjectProvider<AgentSnapshotApi> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(api);
        return p;
    }

    private static DelegationGrantCheck check(AgentSnapshotApi snapshots, Set<String> grantClasses) {
        GrantService grants = mock(GrantService.class);
        when(grants.get("grt1")).thenReturn(new DelegationGrant("grt1", null, "user:1", null,
                null, Set.of("mcp.pay.run", "mcp.other.write", "kb.query"), grantClasses,
                Set.of(), Map.of(), Map.of(), null, Set.of(), null,
                Instant.now().plusSeconds(600), GrantStatus.ACTIVE, null));
        return new DelegationGrantCheck(grants, registry(), snapshotProvider(snapshots));
    }

    private static ToolGateway.ToolCallContext ctx() {
        return ToolCallContexts.forTurn(USER, "thr", "turn", "av1", "chat",
                List.of(), Map.of(), "grt1");
    }

    @Test
    void classifiedWriteToolInGrantClassesAllows() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(CHARTER_JSON)));
        DelegationGrantCheck check = check(api, Set.of("pay"));
        assertNull(check.check(ctx(), "mcp.pay.run", Map.of()));
    }

    @Test
    void classifiedWriteToolNotInGrantClassesDenied() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(CHARTER_JSON)));
        DelegationGrantCheck check = check(api, Set.of("refund"));
        String deny = check.check(ctx(), "mcp.pay.run", Map.of());
        assertTrue(deny.startsWith("GRANT_DENIED"), deny);
        assertTrue(deny.contains("动作类别不在授予内 pay"), deny);
    }

    @Test
    void unclassifiedWriteToolDenied() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(CHARTER_JSON)));
        // mcp.other.write 在授予 tools 内但未归入任何动作类别（charter patterns 只覆盖 mcp.pay.*）
        DelegationGrantCheck check = check(api, Set.of("pay"));
        String deny = check.check(ctx(), "mcp.other.write", Map.of());
        assertTrue(deny.startsWith("GRANT_DENIED"), deny);
        assertTrue(deny.contains("工具未归入任何动作类别"), deny);
    }

    @Test
    void readToolBypassesClassCheck() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(CHARTER_JSON)));
        DelegationGrantCheck check = check(api, Set.of("pay"));
        assertNull(check.check(ctx(), "kb.query", Map.of()), "READ 工具不参与类别收紧");
    }

    @Test
    void emptyGrantClassesDenyAnyWrite() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(CHARTER_JSON)));
        DelegationGrantCheck check = check(api, Set.of());
        String deny = check.check(ctx(), "mcp.pay.run", Map.of());
        assertTrue(deny.startsWith("GRANT_DENIED"), deny);
    }

    @Test
    void charterMissingKeepsLegacyAllow() throws Exception {
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get("av1")).thenReturn(snapshot(charterSpec(null)));
        DelegationGrantCheck check = check(api, Set.of());
        assertNull(check.check(ctx(), "mcp.pay.run", Map.of()),
                "无授权书（存量链路 / TRU-01 关闭）保持放行兼容");
    }

    @Test
    void snapshotUnavailableKeepsLegacyAllow() {
        // snapshots 存在但 get 抛异常（降级放行）
        AgentSnapshotApi api = mock(AgentSnapshotApi.class);
        when(api.get(any())).thenThrow(new RuntimeException("snapshot down"));
        DelegationGrantCheck check = check(api, Set.of());
        assertNull(check.check(ctx(), "mcp.pay.run", Map.of()));
    }

    @Test
    void noSnapshotProviderKeepsLegacyAllow() {
        DelegationGrantCheck check = check(null, Set.of("pay"));
        assertNull(check.check(ctx(), "mcp.pay.run", Map.of()));
    }
}

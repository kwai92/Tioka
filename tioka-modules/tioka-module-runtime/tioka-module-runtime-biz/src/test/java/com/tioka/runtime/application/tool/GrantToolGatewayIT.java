package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.EchoToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.kernel.approval.ApprovalPolicyResolver;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MAS-01 ToolGateway 第 2.5 步部分断言（M3-01 L118-121 离线版）：
 * 子调父有己无工具 DENIED / scope 越界 DENIED / listTools 按授予过滤 / kb 交集剔除。
 * OnBehalfOfIT 端到端与动作类别断言被 TRU-02 阻塞，见批次文档登记。
 */
class GrantToolGatewayIT {
    Principal user = new Principal("1", "ws", PrincipalType.USER, Set.of(), Set.of("*"), "password");
    DelegationGrant grant;

    @BeforeEach
    void setUp() {
        grant = new DelegationGrant("grt_child", "grt_parent", "user:1", null,
                List.of(), Set.of("echo", "kb.search"), Set.of(),
                Set.of(), Map.of("kb", Set.of("kb_a")), Map.of("contractId", List.of("HT-1")),
                null, Set.of(), null,
                Instant.now().plus(Duration.ofHours(1)), GrantStatus.ACTIVE, null);
    }

    GrantService grantService(DelegationGrant g) {
        GrantService gs = mock(GrantService.class);
        when(gs.get("grt_child")).thenReturn(g);
        return gs;
    }

    ToolGateway.ToolCallContext ctx(String grantId) {
        return ToolCallContexts.forTurn(user, "thr", "turn", "av", "chat",
                List.of(), Map.of(), grantId);
    }

    @Test
    void nullGrantIdAllows() {
        DelegationGrantCheck check = new DelegationGrantCheck(grantService(grant), new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        assertNull(check.check(ctx(null), "echo", Map.of()));
    }

    @Test
    void childCannotUseParentOnlyTool() {
        DelegationGrantCheck check = new DelegationGrantCheck(grantService(grant), new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        String deny = check.check(ctx("grt_child"), "biz.erp.writeOff", Map.of());
        assertTrue(deny.contains("不在委派授予内"), deny);
    }

    @Test
    void inactiveGrantDenied() {
        DelegationGrant reaped = new DelegationGrant(grant.grantId(), grant.parentGrantId(),
                grant.rootPrincipalRef(), grant.onBehalfOf(), grant.chain(), grant.tools(),
                grant.actionClasses(), grant.skillVersionIds(), grant.resources(), grant.scope(),
                grant.maxDataLabel(), grant.egressHosts(), grant.budget(), grant.expiresAt(),
                GrantStatus.REAPED, grant.hash());
        DelegationGrantCheck check = new DelegationGrantCheck(grantService(reaped), new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        String deny = check.check(ctx("grt_child"), "echo", Map.of());
        assertTrue(deny.startsWith("GRANT_INACTIVE"), deny);
    }

    @Test
    void scopeEnforced() {
        DelegationGrantCheck check = new DelegationGrantCheck(grantService(grant), new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        String deny = check.check(ctx("grt_child"), "echo", Map.of("contractId", "HT-2"));
        assertTrue(deny.contains("超出任务范围 contractId"), deny);
        assertNull(check.check(ctx("grt_child"), "echo", Map.of("contractId", "HT-1")));
    }

    @Test
    void kbArgsFilteredToGrantIntersection() {
        DelegationGrantCheck check = new DelegationGrantCheck(grantService(grant), new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        // 交集剔除：kb_a 保留、kb_z 剔除
        Map<String, Object> args = new HashMap<>(Map.of("kbIds", new ArrayList<>(List.of("kb_a", "kb_z"))));
        assertNull(check.check(ctx("grt_child"), "kb.search", args));
        assertEquals(List.of("kb_a"), args.get("kbIds"));
        // 全部不在授予内 → DENIED
        Map<String, Object> all = Map.of("kbIds", List.of("kb_z"));
        String deny = check.check(ctx("grt_child"), "kb.search", all);
        assertTrue(deny.contains("知识库不在授予内"), deny);
    }

    @Test
    void pipelineRejectsAtStep2() {
        // 可解析（registry 有 echo）但不在授予内 → 第 2.5 步拒绝，步号记 step=2
        DelegationGrant narrowGrant = new DelegationGrant(grant.grantId(), grant.parentGrantId(),
                grant.rootPrincipalRef(), grant.onBehalfOf(), grant.chain(), Set.of("kb.search"),
                grant.actionClasses(), grant.skillVersionIds(), grant.resources(), grant.scope(),
                grant.maxDataLabel(), grant.egressHosts(), grant.budget(), grant.expiresAt(),
                grant.status(), grant.hash());
        GrantService gs = grantService(narrowGrant);
        DelegationGrantCheck check = new DelegationGrantCheck(gs, new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        ToolRegistry registry = new ToolRegistry(List.of(new EchoToolProvider()));
        PrincipalHolder holder = new PrincipalHolder() {
            @Override public Principal current() { return user; }
            @Override public Optional<Principal> find() { return Optional.of(user); }
            @Override public <T> T runAs(Principal p, Supplier<T> body) { return body.get(); }
        };
        PrincipalLoaderApi loader = (uid, ws) -> user;
        ResourceGuard guard = new ResourceGuard() {
            @Override public void check(String t, String id, AccessLevel l) {}
            @Override public boolean allows(String t, String id, AccessLevel l) { return true; }
            @Override public Set<String> visibleIds(String t, AccessLevel l) { return Set.of(); }
            @Override public VisibilityFilter filter(String t, AccessLevel l) { return VisibilityFilter.allowAll(); }
        };
        com.tioka.runtime.application.tool.ToolRpmLimiter rpm = mock(ToolRpmLimiter.class);
        when(rpm.tryAcquire(any(), any())).thenReturn(true);
        ToolCallPipeline pipeline = new ToolCallPipeline(registry, check, guard, holder, loader,
                rpm, mock(ObjectProvider.class), new ObjectMapper());
        var result = pipeline.authorize(ctx("grt_child"), "echo", Map.of("text", "hi"));
        assertTrue(result instanceof ToolCallPipeline.AuthorizeResult.Reject);
        var reject = (ToolCallPipeline.AuthorizeResult.Reject) result;
        assertEquals(2, reject.step(), "授予拒绝步号记 step=2");
        assertTrue(reject.reason().contains("不在委派授予内"));
    }

    @Test
    void listToolsFilteredByGrant() throws Exception {
        ToolProvider extra = new ToolProvider() {
            @Override public String name() { return "builtin"; }
            @Override public ToolResult invoke(String toolName, Map<String, Object> arguments) {
                return new ToolResult(false, "not invoked");
            }
            @Override public List<ToolDescriptor> listTools(String av) {
                return List.of(ToolDescriptor.builder().name("biz.erp.writeOff").build());
            }
        };
        ToolRegistry registry2 = new ToolRegistry(List.of(extra, new EchoToolProvider()));
        com.tioka.runtime.application.DefaultToolGateway gw = new com.tioka.runtime.application.DefaultToolGateway(
                registry2, mockPipeline(), grantService(grant),
                auditNone(), degradedNone(), new ObjectMapper(),
                mock(com.tioka.runtime.application.gateway.PreparedCallStore.class),
                mock(com.tioka.runtime.application.gateway.ChangeSetFactory.class),
                mock(ApprovalPolicyResolver.class),
                new com.tioka.runtime.application.pipeline.ToolPipelineStepRegistry(List.of()),
                (ObjectProvider<com.tioka.runtime.application.tool.DailyCounterStore>) mock(ObjectProvider.class));
        List<ToolProvider.ToolDescriptor> tools = gw.listTools(ctx("grt_child"));
        assertTrue(tools.stream().anyMatch(d -> d.name().equals("echo")));
        assertFalse(tools.stream().anyMatch(d -> d.name().equals("biz.erp.writeOff")),
                "子 Turn 工具列表不含授予外工具");
        // 无授予 → 全量
        List<ToolProvider.ToolDescriptor> all = gw.listTools(ctx(null));
        assertTrue(all.stream().anyMatch(d -> d.name().equals("biz.erp.writeOff")));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.tioka.system.api.audit.AuditApi> auditNone() {
        ObjectProvider<com.tioka.system.api.audit.AuditApi> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.tioka.system.api.audit.AuditDegradedSink> degradedNone() {
        ObjectProvider<com.tioka.system.api.audit.AuditDegradedSink> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private ToolCallPipeline mockPipeline() {
        GrantService gs = grantService(grant);
        DelegationGrantCheck check = new DelegationGrantCheck(gs, new ToolRegistry(List.of()),
                (ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(ObjectProvider.class));
        ToolRegistry registry = new ToolRegistry(List.of(new EchoToolProvider()));
        PrincipalHolder holder = new PrincipalHolder() {
            @Override public Principal current() { return user; }
            @Override public Optional<Principal> find() { return Optional.of(user); }
            @Override public <T> T runAs(Principal p, Supplier<T> body) { return body.get(); }
        };
        ResourceGuard guard = new ResourceGuard() {
            @Override public void check(String t, String id, AccessLevel l) {}
            @Override public boolean allows(String t, String id, AccessLevel l) { return true; }
            @Override public Set<String> visibleIds(String t, AccessLevel l) { return Set.of(); }
            @Override public VisibilityFilter filter(String t, AccessLevel l) { return VisibilityFilter.allowAll(); }
        };
        com.tioka.runtime.application.tool.ToolRpmLimiter rpm = mock(ToolRpmLimiter.class);
        when(rpm.tryAcquire(any(), any())).thenReturn(true);
        return new ToolCallPipeline(registry, check, guard, holder,
                (PrincipalLoaderApi) (uid, ws) -> user, rpm, mock(ObjectProvider.class), new ObjectMapper());
    }
}

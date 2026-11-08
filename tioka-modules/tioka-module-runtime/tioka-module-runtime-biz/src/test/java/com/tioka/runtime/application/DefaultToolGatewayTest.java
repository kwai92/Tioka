package com.tioka.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.spi.GrantCheck;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.gateway.ChangeSetFactory;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.application.pipeline.ToolPipelineStepRegistry;
import com.tioka.runtime.application.tool.ToolCallPipeline;
import com.tioka.runtime.application.tool.ToolRpmLimiter;
import com.tioka.runtime.kernel.approval.ApprovalPolicyResolver;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultToolGatewayTest {
    private DefaultToolGateway gw;
    private Principal user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        user = new Principal("1", "ws", PrincipalType.USER, Set.of("builder"), Set.of("*"), "password");
        ToolProvider echo = new EchoToolProvider();
        ToolRegistry registry = new ToolRegistry(List.of(echo));
        PrincipalHolder holder = new PrincipalHolder() {
            @Override public Principal current() { return user; }
            @Override public Optional<Principal> find() { return Optional.of(user); }
            @Override public <T> T runAs(Principal principal, Supplier<T> body) { return body.get(); }
        };
        PrincipalLoaderApi loader = (uid, ws) -> user;
        ResourceGuard guard = new ResourceGuard() {
            @Override public void check(String resourceType, String resourceId, AccessLevel level) {}
            @Override public boolean allows(String resourceType, String resourceId, AccessLevel level) {
                return true;
            }
            @Override public Set<String> visibleIds(String resourceType, AccessLevel level) {
                return Set.of();
            }
            @Override public VisibilityFilter filter(String resourceType, AccessLevel level) {
                return VisibilityFilter.allowAll();
            }
        };
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        ToolRpmLimiter rpm = new ToolRpmLimiter(redis);
        GrantCheck grant = (ctx, name, args) -> null;
        ObjectProvider<EgressGuard> egress = mock(ObjectProvider.class);
        when(egress.getIfAvailable()).thenReturn(null);
        ToolCallPipeline pipeline = new ToolCallPipeline(
                registry, grant, guard, holder, loader, rpm, egress, new ObjectMapper());
        ObjectProvider<AuditApi> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(null);
        ObjectProvider<AuditDegradedSink> degraded = mock(ObjectProvider.class);
        when(degraded.getIfAvailable()).thenReturn(null);
        PreparedCallStore preparedCalls = mock(PreparedCallStore.class);
        ChangeSetFactory changeSets = mock(ChangeSetFactory.class);
        ApprovalPolicyResolver policies = mock(ApprovalPolicyResolver.class);
        ApprovalPolicy allow = mock(ApprovalPolicy.class);
        when(allow.evaluate(any())).thenReturn(new ApprovalPolicy.Allow());
        when(policies.resolve(any(), any())).thenReturn(allow);
        gw = new DefaultToolGateway(registry, pipeline, audit, degraded, new ObjectMapper(),
                preparedCalls, changeSets, policies, new ToolPipelineStepRegistry(List.of()));
    }

    private ToolGateway.ToolCallContext ctx(String agentVersionId) {
        return ToolCallContexts.forTurn(user, "thr", "turn", agentVersionId, "chat", List.of(), Map.of());
    }

    @Test
    void deniesUnknownTool() {
        var r = gw.call(ctx("av"), "not_granted_tool", Map.of());
        assertFalse(r.ok());
        assertTrue(r.content().contains("工具不存在") || r.content().contains("无权"));
    }

    @Test
    void allowsEcho() {
        var r = gw.call(ctx("av"), "echo", Map.of("text", "hi"));
        assertTrue(r.ok());
        assertEquals("hi", r.content());
    }

    @Test
    void rejectsBlankAgentVersion() {
        var r = gw.call(ctx(""), "echo", Map.of("text", "hi"));
        assertFalse(r.ok());
    }
}

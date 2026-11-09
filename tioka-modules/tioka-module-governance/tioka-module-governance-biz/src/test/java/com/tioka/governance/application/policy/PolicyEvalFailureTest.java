package com.tioka.governance.application.policy;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.PolicyEvalException;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GOV-07 求值失败兜底离线测试（M3-05 §1.3/§1.4）：求值超时/出错时集合内存在 ENFORCE →
 * DENY + 审计（policy_error）；全 DRYRUN → ALLOW + 告警；管理操作权限校验。
 */
class PolicyEvalFailureTest {

    private PolicyStore store;
    private PolicyCache cache;
    private PolicyEvaluator evaluator;
    private PolicyChangeNotifier notifier;
    private AuditApi audit;
    private PrincipalHolder principals;
    private DefaultPolicyService service;

    private static final PolicyEvalRequest REQ = new PolicyEvalRequest(
            "user:1", "mcp.pay.run", "tool", "mcp.pay.run",
            Map.of("amountMicros", 1L), Map.of("workspaceId", "ws1"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(PolicyStore.class);
        cache = mock(PolicyCache.class);
        evaluator = mock(PolicyEvaluator.class);
        notifier = mock(PolicyChangeNotifier.class);
        audit = mock(AuditApi.class);
        ObjectProvider<AuditApi> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        principals = mock(PrincipalHolder.class);
        when(principals.current()).thenReturn(user(Set.of(Permissions.GOV_POLICY_ADMIN)));
        PolicyProperties props = new PolicyProperties();
        service = new DefaultPolicyService(store, cache, evaluator, notifier, props,
                auditProvider, principals);
    }

    private static Principal user(Set<String> permissions) {
        return new Principal("1", "ws1", PrincipalType.USER, Set.of(), permissions, "password");
    }

    private static PolicyCache.Entry entry(List<ResolvedPolicy> policies, int version) {
        return new PolicyCache.Entry(policies, version, Instant.now().plusSeconds(60));
    }

    private static ResolvedPolicy enforce() {
        return ResolvedPolicy.forbid("p1", "forbid(principal, action, resource);",
                PolicyDecision.DENY, PolicyMode.ENFORCE);
    }

    private static ResolvedPolicy dryrun() {
        return ResolvedPolicy.forbid("p2", "forbid(principal, action, resource);",
                PolicyDecision.DENY, PolicyMode.DRYRUN);
    }

    private void evalAlwaysFails() throws PolicyEvalException {
        when(evaluator.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new PolicyEvalException("policy_eval_timeout", null));
    }

    @Test
    void evalFailureWithEnforceDeniesAndAudits() throws PolicyEvalException {
        when(cache.get("ws1")).thenReturn(entry(List.of(enforce()), 7));
        evalAlwaysFails();
        PolicyEvalResult r = service.evaluate(REQ);
        assertEquals(PolicyDecision.DENY, r.decision());
        assertEquals("7", r.policySetVersion());
        assertEquals(true, r.evalError());
        verify(audit).recordIndependent(any());
    }

    @Test
    void evalFailureDryrunOnlyAllowsAndAudits() throws PolicyEvalException {
        when(cache.get("ws1")).thenReturn(entry(List.of(dryrun()), 3));
        evalAlwaysFails();
        PolicyEvalResult r = service.evaluate(REQ);
        assertEquals(PolicyDecision.ALLOW, r.decision());
        assertEquals(true, r.evalError());
        // DRYRUN 集合失败按告警处理（outcome=SUCCESS → record 通道）
        verify(audit).record(any());
        verify(audit, never()).recordIndependent(any());
    }

    @Test
    void evalSuccessNoAudit() throws PolicyEvalException {
        when(cache.get("ws1")).thenReturn(entry(List.of(enforce()), 7));
        when(evaluator.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PolicyEvalResult(PolicyDecision.ALLOW, List.of(), "7", false));
        PolicyEvalResult r = service.evaluate(REQ);
        assertEquals(PolicyDecision.ALLOW, r.decision());
        assertEquals(false, r.evalError());
        verify(audit, never()).recordIndependent(any());
        verify(audit, never()).record(any());
    }

    @Test
    void setModeRequiresGovPolicyAdmin() {
        when(principals.current()).thenReturn(user(Set.of()));
        BizException e = assertThrows(BizException.class, () -> service.setMode("pol_1", PolicyMode.ENFORCE));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(store, never()).setMode(any(), any());
    }

    @Test
    void disableRequiresGovPolicyAdmin() {
        when(principals.current()).thenReturn(user(Set.of()));
        assertThrows(BizException.class, () -> service.disable("pol_1"));
        verify(store, never()).disable(any());
    }

    @Test
    void setModeWithPermissionDelegatesAndNotifies() {
        com.tioka.governance.infrastructure.persistence.GovPolicyDO row =
                new com.tioka.governance.infrastructure.persistence.GovPolicyDO();
        row.setId("pol_1");
        row.setWorkspaceId("ws1");
        row.setVersion(1);
        row.setSource(PolicyStore.SOURCE_IMPORT);
        row.setPolicyId("p1");
        row.setCedarText("forbid(principal, action, resource);");
        row.setMode(PolicyMode.ENFORCE.name());
        row.setEffect(PolicyDecision.DENY.name());
        row.setStatus(PolicyStore.STATUS_ACTIVE);
        when(store.setMode("pol_1", PolicyMode.ENFORCE)).thenReturn(row);
        when(store.detail("pol_1")).thenReturn(new PolicyAdminApi.PolicyView(
                row.getId(), row.getWorkspaceId(), row.getVersion(), row.getSource(),
                row.getPolicyId(), row.getCedarText(), PolicyMode.ENFORCE,
                PolicyDecision.DENY, PolicyStore.STATUS_ACTIVE));
        when(store.currentSetVersion("ws1")).thenReturn(4);
        var view = service.setMode("pol_1", PolicyMode.ENFORCE);
        assertEquals(PolicyMode.ENFORCE, view.mode());
        verify(notifier).notifyChanged("ws1", 4, PolicyStore.SOURCE_IMPORT, PolicyMode.ENFORCE.name());
        verify(audit).record(any());
    }

    @Test
    void auditOutcomeDeniedOnEnforceFailure() throws PolicyEvalException {
        // ENFORCE 集合失败 → 审计 outcome = DENIED（recordIndependent 通道）
        when(cache.get("ws1")).thenReturn(entry(List.of(enforce(), dryrun()), 2));
        evalAlwaysFails();
        service.evaluate(REQ);
        org.mockito.ArgumentCaptor<com.tioka.system.api.audit.AuditEvent> ev =
                org.mockito.ArgumentCaptor.forClass(com.tioka.system.api.audit.AuditEvent.class);
        verify(audit).recordIndependent(ev.capture());
        assertEquals("tool.policy_eval_failed", ev.getValue().action());
        assertEquals(AuditOutcome.DENIED, ev.getValue().outcome());
    }
}

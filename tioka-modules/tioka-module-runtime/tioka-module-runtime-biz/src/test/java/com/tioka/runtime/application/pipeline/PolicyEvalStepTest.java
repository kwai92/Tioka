package com.tioka.runtime.application.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.policy.PolicyApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyHit;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.application.tool.DailyCounterStore;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具管线第 9 步「Cedar 求值」离线测试（GOV-07 / TRU-02，M3-05 §0/§1.2）：
 * DENY → POLICY_DENIED(40340)；ESCALATE → fourEyes 标记转审批；DRYRUN 命中照常继续 +
 * 审计；开关关闭 / API 缺失整步跳过；context 按 §1.2 组装。
 */
class PolicyEvalStepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Principal USER = new Principal("1", "ws1", PrincipalType.USER,
            Set.of(), Set.of("*"), "password");

    private PolicyApi policyApi;
    private AuditApi audit;
    private DailyCounterStore counters;
    private GrantService grants;
    private AgentSnapshotApi snapshots;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policyApi = mock(PolicyApi.class);
        audit = mock(AuditApi.class);
        counters = mock(DailyCounterStore.class);
        grants = mock(GrantService.class);
        snapshots = mock(AgentSnapshotApi.class);
    }

    private PolicyEvalPipelineStep newStep(boolean enabled) {
        return new PolicyEvalPipelineStep(
                provider(policyApi), provider(snapshots), provider(grants),
                provider(counters), provider(audit), MAPPER, enabled);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    /** 授权书 JSON：pay 类别（mcp.pay.*），fourEyes=true。 */
    private static AgentSpec charterSpec(String charterJson) throws Exception {
        return new AgentSpec(1, null, null, null, null, null, null, null, null, null,
                charterJson == null ? null : MAPPER.readTree(charterJson),
                null, null, null, null, null, null, null);
    }

    private static final String CHARTER_JSON = """
            {"actionClasses":[{"code":"pay","name":"支付","toolPatterns":["mcp.pay.*"],
              "fourEyes":true,"ceiling":"L2","limits":{"perCallAmountMicros":10000}}]}
            """;

    private static AgentSnapshotApi.AgentSnapshot snapshot(AgentSpec spec) {
        return new AgentSnapshotApi.AgentSnapshot("av1", "ag1", 1, "ws1", "u1", "hash", spec);
    }

    private static ToolGateway.ToolCallContext call(String grantId) {
        return ToolCallContexts.forTurn(USER, "thr1", "turn1", "av1", "chat",
                List.of(), Map.of(), grantId);
    }

    private static ToolPipelineContext ctx(String grantId, Map<String, Object> args) {
        return new ToolPipelineContext(call(grantId), "mcp.pay.run", args);
    }

    private static PolicyEvalResult result(PolicyDecision decision, PolicyMode mode) {
        return new PolicyEvalResult(decision,
                List.of(new PolicyHit("charter:av1:pay:escalate-amount", mode, decision)),
                "3", false);
    }

    private void snapshotWithCharter(String charterJson) throws Exception {
        when(snapshots.get("av1")).thenReturn(snapshot(charterSpec(charterJson)));
    }

    @Test
    void denyThrowsPolicyDeniedWithPolicyId() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.zero());
        when(policyApi.evaluate(any())).thenReturn(result(PolicyDecision.DENY, PolicyMode.ENFORCE));
        ToolPipelineContext c = ctx("grt1", Map.of("amountMicros", 20000L));
        BizException e = assertThrows(BizException.class, () -> newStep(true).execute(c));
        assertEquals(ErrorCode.POLICY_DENIED, e.getErrorCode(), "40340");
        assertTrue(e.getMessage().contains("charter:av1:pay:escalate-amount"), e.getMessage());
        assertEquals("DENY", c.get("policy.decision", String.class), "拒绝前属性已写入（审计可还原）");
        assertEquals(List.of("charter:av1:pay:escalate-amount"), c.get("policy.hits", List.class));
    }

    @Test
    void escalateSetsFourEyesFlagFromCharter() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.zero());
        when(policyApi.evaluate(any())).thenReturn(result(PolicyDecision.ESCALATE, PolicyMode.ENFORCE));
        ToolPipelineContext c = ctx("grt1", Map.of("amountMicros", 20000L));
        assertEquals(ToolPipelineVerdict.ESCALATE, newStep(true).execute(c));
        assertEquals(true, c.get("policy.fourEyes", Boolean.class), "授权书 fourEyes=true");
        assertEquals("pay", c.get("policy.actionClass", String.class));
    }

    @Test
    void dryRunHitContinuesAndAuditsWouldDeny() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.zero());
        when(policyApi.evaluate(any())).thenReturn(result(PolicyDecision.DENY, PolicyMode.DRYRUN));
        ToolPipelineContext c = ctx("grt1", Map.of("amountMicros", 20000L));
        assertEquals(ToolPipelineVerdict.CONTINUE, newStep(true).execute(c),
                "DRYRUN 命中不拒绝，照常继续");
        ArgumentCaptor<com.tioka.system.api.audit.AuditEvent> ev =
                ArgumentCaptor.forClass(com.tioka.system.api.audit.AuditEvent.class);
        verify(audit).recordIndependent(ev.capture());
        assertEquals("tool.policy_dryrun", ev.getValue().action());
        assertEquals(AuditOutcome.DENIED, ev.getValue().outcome());
        assertEquals("dryrun_would_deny", ev.getValue().reason());
    }

    @Test
    void dryRunEscalateHitContinuesAndAuditsWouldEscalate() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.zero());
        when(policyApi.evaluate(any())).thenReturn(result(PolicyDecision.ESCALATE, PolicyMode.DRYRUN));
        ToolPipelineContext c = ctx(null, Map.of("amountMicros", 20000L));
        assertEquals(ToolPipelineVerdict.CONTINUE, newStep(true).execute(c));
        ArgumentCaptor<com.tioka.system.api.audit.AuditEvent> ev =
                ArgumentCaptor.forClass(com.tioka.system.api.audit.AuditEvent.class);
        verify(audit).recordIndependent(ev.capture());
        assertEquals("dryrun_would_escalate", ev.getValue().reason());
    }

    @Test
    void allowContinuesWithoutAudit() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.zero());
        when(policyApi.evaluate(any())).thenReturn(
                new PolicyEvalResult(PolicyDecision.ALLOW, List.of(), "3", false));
        ToolPipelineContext c = ctx(null, Map.of("amountMicros", 1L));
        assertEquals(ToolPipelineVerdict.CONTINUE, newStep(true).execute(c));
        verify(audit, never()).recordIndependent(any());
        verify(audit, never()).record(any());
    }

    @Test
    void disabledSwitchSkipsStepAndAudits() {
        ToolPipelineContext c = ctx(null, Map.of());
        assertEquals(ToolPipelineVerdict.CONTINUE, newStep(false).execute(c));
        ArgumentCaptor<com.tioka.system.api.audit.AuditEvent> ev =
                ArgumentCaptor.forClass(com.tioka.system.api.audit.AuditEvent.class);
        verify(audit).recordIndependent(ev.capture());
        assertEquals("tool.policy_skipped", ev.getValue().action());
        assertEquals("policy_disabled", ev.getValue().reason());
        verify(policyApi, never()).evaluate(any());
    }

    @Test
    void missingPolicyApiSkipsStep() {
        PolicyEvalPipelineStep step = new PolicyEvalPipelineStep(
                emptyProvider(), provider(snapshots), provider(grants),
                provider(counters), provider(audit), MAPPER, true);
        assertEquals(ToolPipelineVerdict.CONTINUE, step.execute(ctx(null, Map.of())));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @Test
    void contextAssemblyFollowsSpec() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(org.mockito.ArgumentMatchers.eq("ag1"),
                org.mockito.ArgumentMatchers.eq("pay"), any())).thenReturn(
                new DailyCounterStore.DailyUsage(3000L, 7, false));
        when(grants.get("grt1")).thenReturn(new DelegationGrant("grt1", null, "user:1", "user:9",
                null, Set.of("mcp.pay.run"), Set.of("pay"), Set.of(), Map.of(), Map.of(),
                null, Set.of(), null, Instant.now().plusSeconds(600), GrantStatus.ACTIVE, null));
        when(policyApi.evaluate(any())).thenReturn(
                new PolicyEvalResult(PolicyDecision.ALLOW, List.of(), "3", false));
        newStep(true).execute(ctx("grt1", Map.of("amountMicros", 20000L)));

        ArgumentCaptor<PolicyEvalRequest> req = ArgumentCaptor.forClass(PolicyEvalRequest.class);
        verify(policyApi).evaluate(req.capture());
        Map<String, Object> context = req.getValue().context();
        assertEquals("ws1", context.get("workspaceId"));
        assertEquals("av1", context.get("agentVersion"));
        assertEquals("ag1", context.get("agentId"), "快照 agentId");
        assertEquals("pay", context.get("actionClass"), "类型化 Charter 归类");
        assertEquals("L2", context.get("autonomyLevel"), "ceiling 即自治级别");
        assertEquals("chat", context.get("origin"));
        assertEquals(List.of(), context.get("chain"), "chain 为空（grant.chain null）");
        assertEquals(1, context.get("depth"));
        assertEquals("user:9", context.get("onBehalfOf"));
        assertEquals(3000L, context.get("dailyAmountMicros"));
        assertEquals(Long.valueOf(7), context.get("dailyRecords"));
        assertEquals("user:1", req.getValue().principalRef(), "principal 类型:ID");
        assertEquals(Map.of("amountMicros", 20000L), req.getValue().resourceAttrs());
    }

    @Test
    void snapshotLoadFailureStillEvaluates() throws Exception {
        when(snapshots.get("av1")).thenThrow(new RuntimeException("snapshot down"));
        when(policyApi.evaluate(any())).thenReturn(
                new PolicyEvalResult(PolicyDecision.ALLOW, List.of(), "3", false));
        ToolPipelineContext c = ctx(null, Map.of("amountMicros", 1L));
        assertEquals(ToolPipelineVerdict.CONTINUE, newStep(true).execute(c),
                "快照加载失败不阻断（降级为空类别）");
        ArgumentCaptor<PolicyEvalRequest> req = ArgumentCaptor.forClass(PolicyEvalRequest.class);
        verify(policyApi).evaluate(req.capture());
        assertEquals("", req.getValue().context().get("actionClass"));
        assertEquals("L1", req.getValue().context().get("autonomyLevel"), "未归类按 L1");
    }

    @Test
    void unknownDailyUsageCarriesMaxSentinel() throws Exception {
        snapshotWithCharter(CHARTER_JSON);
        when(counters.get(any(), any(), any())).thenReturn(DailyCounterStore.DailyUsage.unknownUsage());
        when(policyApi.evaluate(any())).thenReturn(
                new PolicyEvalResult(PolicyDecision.ALLOW, List.of(), "3", false));
        newStep(true).execute(ctx(null, Map.of()));
        ArgumentCaptor<PolicyEvalRequest> req = ArgumentCaptor.forClass(PolicyEvalRequest.class);
        verify(policyApi).evaluate(req.capture());
        assertEquals(Long.MAX_VALUE / 4, req.getValue().context().get("dailyAmountMicros"),
                "重算期间按已用满（宁可多转审批）");
    }

    @Test
    void orderIsNinthStep() {
        assertEquals(9.0, newStep(true).order());
        assertEquals("policy-eval", newStep(true).name());
    }
}

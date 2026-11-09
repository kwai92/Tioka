package com.tioka.runtime.application.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.governance.api.policy.PolicyApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyHit;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineOrders;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.application.tool.DailyCounterStore;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具管线第 9 步「Cedar 求值」（GOV-07 / TRU-02，M3-05 §0）。首个注册的管线步骤 bean。
 *
 * <ul>
 *   <li>context 按 §1.2：Agent 版本、空间、来源、时间、当日累计、委派链、深度、onBehalfOf
 *       （从 ToolCallContext.grantId 链取）；actionClass 由类型化 Charter 解析（动作组归属）</li>
 *   <li>DENY → {@link ErrorCode#POLICY_DENIED}（40340）拒绝带策略 @id；ESCALATE → 网关已预留
 *       转审批分支（fourEyes 标记经 context 属性传递）</li>
 *   <li>DRYRUN 命中：调用照常继续，审计记「本应拒绝/本应转审批」与策略 @id</li>
 *   <li>{@code tioka.policy.enabled=false}：整步跳过（直接 CONTINUE），写审计</li>
 * </ul>
 */
@Component
public class PolicyEvalPipelineStep implements ToolPipelineStep {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvalPipelineStep.class);

    /** 空间时区（默认 Asia/Shanghai，M3-05 §3.3；存储仍为 UTC）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<PolicyApi> policyApi;
    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;
    private final ObjectProvider<GrantService> grants;
    private final ObjectProvider<DailyCounterStore> dailyCounters;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public PolicyEvalPipelineStep(ObjectProvider<PolicyApi> policyApi,
                                  ObjectProvider<AgentSnapshotApi> agentSnapshots,
                                  ObjectProvider<GrantService> grants,
                                  ObjectProvider<DailyCounterStore> dailyCounters,
                                  ObjectProvider<AuditApi> auditApi,
                                  ObjectMapper mapper,
                                  @Value("${tioka.policy.enabled:true}") boolean enabled) {
        this.policyApi = policyApi;
        this.agentSnapshots = agentSnapshots;
        this.grants = grants;
        this.dailyCounters = dailyCounters;
        this.auditApi = auditApi;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public double order() {
        return ToolPipelineOrders.POLICY_EVAL;
    }

    @Override
    public String name() {
        return "policy-eval";
    }

    @Override
    public ToolPipelineVerdict execute(ToolPipelineContext ctx) {
        if (!enabled) {
            // 紧急开关关闭：第 9 步直接 ALLOW（M3-05 §1.7），写审计便于回溯
            auditSkipped(ctx, "policy_disabled");
            return ToolPipelineVerdict.CONTINUE;
        }
        PolicyApi api = policyApi.getIfAvailable();
        if (api == null) {
            return ToolPipelineVerdict.CONTINUE;
        }
        ToolGateway.ToolCallContext call = ctx.callContext();
        Principal principal = call == null ? null : call.principal();
        SnapshotView snapshot = loadSnapshot(call == null ? null : call.agentVersionId());
        GrantView grantView = loadGrant(call == null ? null : call.grantId());
        Charter.ActionClass actionClass = snapshot.charter() == null
                ? null : snapshot.charter().classify(ctx.toolName());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workspaceId", principal == null ? "" : principal.workspaceId());
        context.put("agentVersion", call == null ? "" : call.agentVersionId());
        context.put("agentId", snapshot.agentId() == null ? "" : snapshot.agentId());
        context.put("origin", call == null ? "" : call.origin());
        context.put("time", Instant.now().toString());
        context.put("actionClass", actionClass == null ? "" : actionClass.code());
        context.put("autonomyLevel", actionClass == null ? "L1"
                : (actionClass.ceiling() == null ? "L2" : actionClass.ceiling()));
        if (grantView != null) {
            context.put("chain", grantView.chain());
            context.put("depth", grantView.depth());
            context.put("onBehalfOf", grantView.onBehalfOf() == null ? "" : grantView.onBehalfOf());
        } else {
            context.put("chain", List.of());
            context.put("depth", 1);
            context.put("onBehalfOf", "");
        }
        DailyCounterStore.DailyUsage usage = dailyUsage(snapshot.agentId(), actionClass);
        context.put("dailyAmountMicros", usage.amountMicros());
        context.put("dailyRecords", usage.records());

        PolicyEvalRequest request = new PolicyEvalRequest(
                principal == null ? "system" : principal.type().name().toLowerCase(Locale.ROOT)
                        + ":" + principal.userId(),
                ctx.toolName(),
                "tool",
                ctx.toolName(),
                resourceAttrs(ctx.args()),
                context);

        PolicyEvalResult result = api.evaluate(request);

        // DRYRUN 命中：结果仍按 ALLOW 返回语义不变，审计记「本应拒绝/本应转审批」与策略 @id
        List<PolicyHit> dryRunHits = result.hits().stream()
                .filter(h -> h.mode() == PolicyMode.DRYRUN)
                .toList();
        if (!dryRunHits.isEmpty()) {
            auditDryRun(ctx, result, dryRunHits);
        }

        List<PolicyHit> enforced = result.hits().stream()
                .filter(h -> h.mode() == PolicyMode.ENFORCE)
                .toList();
        ctx.put("policy.decision", result.decision().name());
        ctx.put("policy.policySetVersion", result.policySetVersion());
        ctx.put("policy.hits", enforced.stream().map(PolicyHit::policyId).toList());

        if (result.decision() == PolicyDecision.DENY && !enforced.isEmpty()) {
            // DENY → FORBIDDEN 拒绝带策略 @id（40340）
            throw new BizException(ErrorCode.POLICY_DENIED,
                    "策略拒绝: " + enforced.stream().map(PolicyHit::policyId)
                            .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (result.decision() == PolicyDecision.ESCALATE && !enforced.isEmpty()) {
            ctx.put("policy.fourEyes", actionClass != null && actionClass.fourEyes());
            ctx.put("policy.actionClass", actionClass == null ? "" : actionClass.code());
            return ToolPipelineVerdict.ESCALATE;
        }
        return ToolPipelineVerdict.CONTINUE;
    }

    private DailyCounterStore.DailyUsage dailyUsage(String agentId, Charter.ActionClass actionClass) {
        DailyCounterStore counters = dailyCounters.getIfAvailable();
        if (counters == null || agentId == null || actionClass == null) {
            return DailyCounterStore.DailyUsage.zero();
        }
        // 重算期间按「已用满」处理：unknown = 宁可多转审批（M3-05 §3.3）
        return counters.get(agentId, actionClass.code(), LocalDate.now(ZONE));
    }

    private SnapshotView loadSnapshot(String agentVersionId) {
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (snapshots == null || agentVersionId == null || agentVersionId.isBlank()) {
            return new SnapshotView(null, null);
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = snapshots.get(agentVersionId);
            AgentSpec spec = snap.spec();
            Charter charter;
            try {
                charter = spec == null ? null : Charter.parse(spec.charter());
            } catch (IllegalArgumentException e) {
                charter = null;
            }
            return new SnapshotView(snap.agentId(), charter);
        } catch (RuntimeException e) {
            log.debug("policy step 快照加载失败 version={}: {}", agentVersionId, e.toString());
            return new SnapshotView(null, null);
        }
    }

    private GrantView loadGrant(String grantId) {
        GrantService service = grants.getIfAvailable();
        if (service == null || grantId == null || grantId.isBlank()) {
            return null;
        }
        DelegationGrant grant = service.get(grantId);
        if (grant == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        if (grant.chain() != null) {
            grant.chain().forEach(hop -> chain.add(hop.agentVersionId()));
        }
        return new GrantView(List.copyOf(chain), grant.chain() == null ? 1 : grant.chain().size(),
                grant.onBehalfOf());
    }

    /** resource 属性：可策略化参数直接透传（金额/记录数等数值型优先）。 */
    private static Map<String, Object> resourceAttrs(Map<String, Object> args) {
        Map<String, Object> out = new HashMap<>();
        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                    out.put(e.getKey(), v);
                }
            }
        }
        return out;
    }

    private void auditSkipped(ToolPipelineContext ctx, String reason) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        ToolGateway.ToolCallContext call = ctx.callContext();
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action("tool.policy_skipped")
                .resourceType("tool")
                .resourceId(ctx.toolName())
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .details(Map.of("step", "policy-eval", "reason", reason));
        if (call != null && call.principal() != null) {
            b.workspaceId(call.principal().workspaceId())
                    .actorType(call.principal().type().name().toLowerCase(Locale.ROOT))
                    .actorId(call.principal().userId());
        }
        try {
            api.recordIndependent(b.build());
        } catch (RuntimeException e) {
            log.warn("policy_skipped 审计写入失败: {}", e.toString());
        }
    }

    private void auditDryRun(ToolPipelineContext ctx, PolicyEvalResult result,
                             List<PolicyHit> hits) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        ToolGateway.ToolCallContext call = ctx.callContext();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("step", "policy-eval");
        details.put("wouldDecision", result.decision().name());
        details.put("policyIds", hits.stream().map(PolicyHit::policyId).toList());
        details.put("policySetVersion", result.policySetVersion());
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action("tool.policy_dryrun")
                .resourceType("tool")
                .resourceId(ctx.toolName())
                .outcome(AuditOutcome.DENIED)
                .reason("dryrun_would_" + result.decision().name().toLowerCase(Locale.ROOT))
                .details(details);
        if (call != null && call.principal() != null) {
            b.workspaceId(call.principal().workspaceId())
                    .actorType(call.principal().type().name().toLowerCase(Locale.ROOT))
                    .actorId(call.principal().userId());
        }
        try {
            api.recordIndependent(b.build());
        } catch (RuntimeException e) {
            log.warn("policy_dryrun 审计写入失败: {}", e.toString());
        }
    }

    private record SnapshotView(String agentId, Charter charter) {}

    private record GrantView(List<String> chain, int depth, String onBehalfOf) {}
}

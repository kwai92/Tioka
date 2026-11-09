package com.tioka.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ApprovalRequestContexts;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.GateDecision;
import com.tioka.runtime.api.spi.PolicyOutcome;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.StaleChangeSetException;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.api.spi.TransactionalTool;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.application.gateway.ChangeSetFactory;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.application.pipeline.ToolPipelineStepRegistry;
import com.tioka.runtime.application.tool.ToolCallPipeline;
import com.tioka.runtime.application.tool.ToolCallPipeline.AuthorizeResult;
import com.tioka.runtime.application.tool.ToolCallPipeline.AuthorizedCall;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.kernel.approval.ApprovalPolicyResolver;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CON-01：authorize → 调用 → 审计；TRU-05：prepare / commit（ADR-033）。
 */
@Component
public class DefaultToolGateway implements ToolGateway {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolGateway.class);
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "api_key", "apikey",
            "access_token", "refresh_token", "client_secret");

    private final ToolRegistry registry;
    private final ToolCallPipeline pipeline;
    private final com.tioka.runtime.api.grant.GrantService grants;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectProvider<AuditDegradedSink> degradedSink;
    private final ObjectMapper mapper;
    private final PreparedCallStore preparedCalls;
    private final ChangeSetFactory changeSets;
    private final ApprovalPolicyResolver policies;
    private final ToolPipelineStepRegistry pipelineSteps;
    private final ObjectProvider<com.tioka.runtime.application.tool.DailyCounterStore> dailyCounters;

    public DefaultToolGateway(ToolRegistry registry,
                              ToolCallPipeline pipeline,
                              com.tioka.runtime.api.grant.GrantService grants,
                              ObjectProvider<AuditApi> auditApi,
                              ObjectProvider<AuditDegradedSink> degradedSink,
                              ObjectMapper mapper,
                              PreparedCallStore preparedCalls,
                              ChangeSetFactory changeSets,
                              ApprovalPolicyResolver policies,
                              ToolPipelineStepRegistry pipelineSteps,
                              ObjectProvider<com.tioka.runtime.application.tool.DailyCounterStore> dailyCounters) {
        this.registry = registry;
        this.pipeline = pipeline;
        this.grants = grants;
        this.auditApi = auditApi;
        this.degradedSink = degradedSink;
        this.mapper = mapper;
        this.preparedCalls = preparedCalls;
        this.changeSets = changeSets;
        this.policies = policies;
        this.pipelineSteps = pipelineSteps == null
                ? new ToolPipelineStepRegistry(List.of())
                : pipelineSteps;
        this.dailyCounters = dailyCounters;
    }

    @Override
    public List<ToolProvider.ToolDescriptor> listTools(ToolCallContext ctx) {
        if (ctx == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            return List.of();
        }
        List<ToolProvider.ToolDescriptor> all = registry.listAll(ctx.agentVersionId(), ctx.agentToolConfig());
        List<String> allow = ctx.toolAllowList();
        if (allow != null && !allow.isEmpty()) {
            all = all.stream().filter(d -> allow.contains(d.name())).toList();
        }
        // MAS-01：listTools 按授予过滤，模型看不到授予外工具（M3-01）
        if (ctx.grantId() != null && !ctx.grantId().isBlank()) {
            com.tioka.runtime.api.grant.DelegationGrant grant = grants.get(ctx.grantId());
            if (grant != null && grant.tools() != null) {
                all = all.stream().filter(d -> grant.tools().contains(d.name())).toList();
            }
        }
        return all;
    }

    @Override
    public ToolProvider.ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args) {
        String name = toolName == null ? "" : toolName;
        Span span = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("tool.invoke")
                .setAttribute("tioka.tool", name)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // call = CON-01 鉴权执行；TRU-05 预演/提交走 prepare/commit，勿在此短路写工具
            // （否则审批续跑经 GatewayToolPort.call 无法真正落工具）
            ToolProvider.ToolResult result = callTracked(ctx, name, args);
            span.setAttribute("tioka.tool.ok", result.ok());
            return result;
        } catch (RuntimeException e) {
            span.setAttribute("tioka.tool.ok", false);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public PreparedCall prepare(ToolCallContext ctx, String toolName, Map<String, Object> args) {
        String name = toolName == null ? "" : toolName;
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        AuthorizeResult authz = pipeline.authorize(ctx, name, safeArgs);
        if (authz instanceof AuthorizeResult.Reject reject) {
            if (reject.rateLimited()) {
                auditDenied(ctx, name, safeArgs, reject.reason(), reject.step(), 0);
                throw new BizException(ErrorCode.RATE_LIMITED, "工具调用过于频繁: " + name);
            }
            String msg;
            if (reject.step() == 1 && "工具不存在".equals(reject.reason())) {
                msg = "工具不存在";
            } else if (reject.step() == 6) {
                msg = reject.reason();
            } else {
                msg = "无权预演工具 " + name + "：" + reject.reason();
            }
            auditDenied(ctx, name, safeArgs, reject.reason(), reject.step(), 0);
            throw new BizException(ErrorCode.FORBIDDEN, msg);
        }
        AuthorizedCall ac = ((AuthorizeResult.Ok) authz).call();
        ToolProvider.ToolDescriptor desc = ac.descriptor();
        ToolRisk risk = desc.risk() == null ? ToolRisk.WRITE : desc.risk();
        Reversibility rev = desc.reversibility() == null
                ? (risk == ToolRisk.READ ? Reversibility.NONE : Reversibility.IRREVERSIBLE)
                : desc.reversibility();

        // P3-00b / K1b：按序号跑已注册步骤；空注册表无副作用（行为 = 现状）
        ToolPipelineContext pipeCtx = new ToolPipelineContext(ctx, name, ac.args());
        ToolPipelineVerdict pipeVerdict;
        try {
            pipeVerdict = pipelineSteps.run(pipeCtx);
        } catch (BizException e) {
            // 第 9 步策略 DENY（POLICY_DENIED 40340）：审计拒绝原因（含策略 @id）后原样抛出
            auditDenied(ctx, name, ac.args(), e.getMessage(), 9, 0);
            throw e;
        }
        if (pipeVerdict == ToolPipelineVerdict.ESCALATE) {
            // GOV-07 / TRU-02：第 9 步 Cedar ESCALATE（超额转审批）
            String stepName = pipeCtx.get("pipeline.step", String.class);
            Boolean fourEyes = pipeCtx.get("policy.fourEyes", Boolean.class);
            ApprovalPolicy.Decision escalated = new ApprovalPolicy.RequireApproval(
                    new ApprovalPolicy.Assignee(List.of(), List.of()),
                    Duration.ofHours(24),
                    (Boolean.TRUE.equals(fourEyes) ? "fourEyes:" : "pipeline:")
                            + (stepName == null ? "escalate" : stepName));
            GateDecision gate = toGate(escalated);
            PolicyOutcome policy = toPolicy(escalated);
            ToolInvocation inv = new ToolInvocation(name, ac.args());
            ChangeSet changeSet = changeSets.estimatedFromArgs(name, inv);
            String itemId = Ids.newId("itm");
            String agentId = ctx.agentVersionId() == null ? "" : ctx.agentVersionId();
            TrustActionDO row = preparedCalls.insertProposed(
                    ctx, agentId, name, name, risk, rev, gate, inv, changeSet, itemId);
            return new PreparedCall(row.getId(), row.getActionClass(), changeSet, policy, gate);
        }

        ToolInvocation inv = new ToolInvocation(name, ac.args());
        ChangeSet changeSet;
        if (ac.provider() instanceof TransactionalTool tx) {
            changeSet = tx.preview(inv);
        } else {
            changeSet = changeSets.estimatedFromArgs(name, inv);
        }

        ApprovalPolicy.Decision decision = evaluatePolicy(ctx, name, ac.args(), desc);
        GateDecision gate = toGate(decision);
        PolicyOutcome policy = toPolicy(decision);

        String itemId = Ids.newId("itm");
        String agentId = ctx.agentVersionId() == null ? "" : ctx.agentVersionId();
        TrustActionDO row = preparedCalls.insertProposed(
                ctx, agentId, name, name, risk, rev, gate, inv, changeSet, itemId);
        return new PreparedCall(row.getId(), row.getActionClass(), changeSet, policy, gate);
    }

    @Override
    public ToolProvider.ToolResult commit(ToolCallContext ctx, String preparedId) {
        TrustActionDO row = preparedCalls.lockForUpdate(preparedId);
        if (PreparedCallStore.STATUS_APPLIED.equals(row.getStatus())) {
            ChangeSet.ApplyReceipt cached = preparedCalls.readReceipt(row);
            String content = cached == null ? "already applied" : writeJson(cached);
            return new ToolProvider.ToolResult(true, content);
        }
        if (preparedCalls.expired(row)) {
            preparedCalls.markStale(row);
            return new ToolProvider.ToolResult(false, "预演已过期，请重新确认后再执行");
        }
        ToolInvocation inv = preparedCalls.readInvocation(row);
        ChangeSet approved = preparedCalls.readChangeSet(row);
        var resolved = registry.resolve(ctx.agentVersionId(),
                ctx == null ? Map.of() : ctx.agentToolConfig(), row.getToolName());
        if (resolved.isEmpty()) {
            preparedCalls.markFailed(row, "tool not found");
            return new ToolProvider.ToolResult(false, "tool not found: " + row.getToolName());
        }
        ToolProvider provider = resolved.get().provider();
        long t0 = System.currentTimeMillis();
        try {
            if (provider instanceof TransactionalTool tx) {
                ChangeSet.ApplyReceipt receipt = tx.apply(inv, approved, row.getItemId());
                preparedCalls.markApplied(row, receipt);
                recordDailyUsage(row, approved);
                ToolProvider.ToolResult ok = new ToolProvider.ToolResult(true, writeJson(receipt));
                auditInvoke(ctx, ctx == null ? null : ctx.principal(), row.getToolName(),
                        inv.arguments(), ok, true, System.currentTimeMillis() - t0, null);
                return ok;
            }
            ToolProvider.ToolResult result = provider.invoke(ctx.agentVersionId(), row.getToolName(),
                    inv.arguments(), ctx == null ? Map.of() : ctx.agentToolConfig());
            if (result.ok()) {
                preparedCalls.markApplied(row, new ChangeSet.ApplyReceipt(
                        row.getItemId(), Map.of(), Instant.now()));
                recordDailyUsage(row, approved);
            } else {
                preparedCalls.markFailed(row, result.content());
            }
            auditInvoke(ctx, ctx == null ? null : ctx.principal(), row.getToolName(),
                    inv.arguments(), result, result.ok(), System.currentTimeMillis() - t0, null);
            return result;
        } catch (StaleChangeSetException e) {
            preparedCalls.markStale(row);
            return new ToolProvider.ToolResult(false, "数据已变化，请重新确认后再执行");
        } catch (RuntimeException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            preparedCalls.markFailed(row, err);
            throw e;
        }
    }

    /**
     * TRU-02 commit 第 4 步：Redis 当日累计加上变更单汇总（M3-05 §0/§3.3）。
     * 撤销扣回归 TRU-07（本批不做）。计数失败不阻断 commit（重算兜底）。
     */
    private void recordDailyUsage(TrustActionDO row, ChangeSet approved) {
        if (row.getActionClass() == null || row.getActionClass().isBlank()) {
            return;
        }
        com.tioka.runtime.application.tool.DailyCounterStore counters = dailyCounters.getIfAvailable();
        if (counters == null) {
            return;
        }
        ChangeSet.Totals totals = approved == null ? null : approved.totals();
        long amount = totals == null || totals.amountMicros() == null ? 0 : totals.amountMicros();
        long records = totals == null ? 0 : totals.records();
        counters.add(row.getAgentId(), row.getActionClass(),
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), amount, records);
    }

    private ToolProvider.ToolResult callTracked(ToolCallContext ctx, String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        long t0 = System.currentTimeMillis();

        AuthorizeResult authz = pipeline.authorize(ctx, name, safeArgs);
        if (authz instanceof AuthorizeResult.Reject reject) {
            if (reject.rateLimited()) {
                auditDenied(ctx, name, safeArgs, reject.reason(), reject.step(), 0);
                throw new BizException(ErrorCode.RATE_LIMITED, "工具调用过于频繁: " + name);
            }
            String msg;
            if (reject.step() == 1 && "工具不存在".equals(reject.reason())) {
                msg = "工具不存在";
            } else if (reject.step() == 6) {
                msg = reject.reason();
            } else {
                msg = "无权调用工具 " + name + "：" + reject.reason();
            }
            ToolProvider.ToolResult denied = new ToolProvider.ToolResult(false, msg);
            auditDenied(ctx, name, safeArgs, reject.reason(), reject.step(),
                    System.currentTimeMillis() - t0);
            return denied;
        }

        AuthorizedCall ac = ((AuthorizeResult.Ok) authz).call();
        ToolProvider.ToolResult result;
        try {
            // M3：透传完整 ctx（agent.delegate 等需要父 Turn / grantId 的 provider 走此重载）
            result = ac.provider().invoke(ctx, ctx.agentVersionId(), name, ac.args(),
                    ctx == null ? Map.of() : ctx.agentToolConfig());
        } catch (RuntimeException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = new ToolProvider.ToolResult(false, err);
            auditInvoke(ctx, ac.principal(), name, ac.args(), result, false,
                    System.currentTimeMillis() - t0, "invoke_error: " + err);
            throw e;
        }
        long dur = System.currentTimeMillis() - t0;
        auditInvoke(ctx, ac.principal(), name, ac.args(), result, result.ok(), dur, null);
        log.info("tool_call thread={} tool={} ok={} durMs={}",
                ctx == null ? null : ctx.threadId(), name, result.ok(), dur);
        return result;
    }

    private ApprovalPolicy.Decision evaluatePolicy(ToolCallContext ctx, String name,
                                                   Map<String, Object> args,
                                                   ToolProvider.ToolDescriptor desc) {
        Principal principal = ctx == null ? null : ctx.principal();
        ApprovalPolicy policy = policies.resolve(null, principal);
        ApprovalPolicy.ApprovalRequestContext req = ApprovalRequestContexts.from(
                principal, ctx == null ? null : ctx.agentVersionId(), name, args, desc, Map.of());
        return policy.evaluate(req);
    }

    private static GateDecision toGate(ApprovalPolicy.Decision d) {
        if (d instanceof ApprovalPolicy.Allow) {
            return GateDecision.AUTO_COMMIT;
        }
        if (d instanceof ApprovalPolicy.Deny) {
            return GateDecision.DENIED;
        }
        return GateDecision.REQUIRE_APPROVAL;
    }

    private static PolicyOutcome toPolicy(ApprovalPolicy.Decision d) {
        if (d instanceof ApprovalPolicy.Allow) {
            return PolicyOutcome.ALLOW;
        }
        if (d instanceof ApprovalPolicy.Deny) {
            return PolicyOutcome.DENY;
        }
        return PolicyOutcome.ESCALATE;
    }

    @Override
    public List<Map<String, Object>> asOpenAiTools(ToolCallContext ctx) {
        return listTools(ctx).stream().map(d -> {
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.put("parameters", d.inputSchema());
            return Map.<String, Object>of("type", "function", "function", fn);
        }).toList();
    }

    private void auditDenied(ToolCallContext ctx, String name, Map<String, Object> args,
                             String reason, int step, long durationMs) {
        Principal p = ctx == null ? null : ctx.principal();
        auditInvoke(ctx, p, name, args,
                new ToolProvider.ToolResult(false, reason),
                false, durationMs, "step" + step + ":" + reason);
    }

    private void auditInvoke(ToolCallContext ctx, Principal principal, String name,
                             Map<String, Object> args, ToolProvider.ToolResult result,
                             boolean ok, long durationMs, String denyReason) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("argsDigest", sha256(args));
        details.put("argsPreview", argsPreview(args));
        details.put("ok", ok);
        details.put("durationMs", durationMs);
        String content = result == null || result.content() == null ? "" : result.content();
        details.put("outputChars", content.length());
        if (denyReason != null) {
            details.put("denyStep", denyReason);
        }
        if (ctx != null) {
            details.put("threadId", ctx.threadId());
            details.put("turnId", ctx.turnId());
            details.put("origin", ctx.origin());
        }
        AuditOutcome outcome = denyReason != null
                ? AuditOutcome.DENIED
                : (ok ? AuditOutcome.SUCCESS : AuditOutcome.ERROR);
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action("tool.invoke")
                .resourceType("tool")
                .resourceId(name)
                .outcome(outcome)
                .reason(denyReason)
                .details(details);
        if (principal != null) {
            b.workspaceId(principal.workspaceId())
                    .actorType(principal.type().name().toLowerCase(Locale.ROOT))
                    .actorId(principal.userId());
        }
        AuditEvent event = b.build();
        try {
            if (outcome == AuditOutcome.SUCCESS) {
                api.record(event);
            } else {
                api.recordIndependent(event);
            }
        } catch (RuntimeException e) {
            AuditDegradedSink sink = degradedSink.getIfAvailable();
            if (sink != null) {
                sink.append(event);
            } else {
                log.error("tool.invoke 审计写入失败且无降级 sink: {}", e.toString());
            }
        }
    }

    private String argsPreview(Map<String, Object> args) {
        try {
            Object masked = maskSensitive(args);
            String json = mapper.writeValueAsString(masked == null ? Map.of() : masked);
            if (json.length() <= 500) {
                return json;
            }
            return json.substring(0, 500);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Object maskSensitive(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (SENSITIVE_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                    out.put(k, "***");
                } else {
                    out.put(k, maskSensitive(e.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DefaultToolGateway::maskSensitive).toList();
        }
        return value;
    }

    private String sha256(Map<String, Object> args) {
        try {
            String json = mapper.writeValueAsString(args == null ? Map.of() : args);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}

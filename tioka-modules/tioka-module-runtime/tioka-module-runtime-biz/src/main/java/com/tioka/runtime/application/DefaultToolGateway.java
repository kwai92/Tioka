package com.tioka.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.tool.ToolCallPipeline;
import com.tioka.runtime.application.tool.ToolCallPipeline.AuthorizeResult;
import com.tioka.runtime.application.tool.ToolCallPipeline.AuthorizedCall;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CON-01：authorize → 调用 → 审计（ADR-029 第 8 条：调用返回后单独写）。
 */
@Component
public class DefaultToolGateway implements ToolGateway {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolGateway.class);
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "api_key", "apikey",
            "access_token", "refresh_token", "client_secret");

    private final ToolRegistry registry;
    private final ToolCallPipeline pipeline;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectProvider<AuditDegradedSink> degradedSink;
    private final ObjectMapper mapper;

    public DefaultToolGateway(ToolRegistry registry,
                              ToolCallPipeline pipeline,
                              ObjectProvider<AuditApi> auditApi,
                              ObjectProvider<AuditDegradedSink> degradedSink,
                              ObjectMapper mapper) {
        this.registry = registry;
        this.pipeline = pipeline;
        this.auditApi = auditApi;
        this.degradedSink = degradedSink;
        this.mapper = mapper;
    }

    @Override
    public List<ToolProvider.ToolDescriptor> listTools(ToolCallContext ctx) {
        if (ctx == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            return List.of();
        }
        List<ToolProvider.ToolDescriptor> all = registry.listAll(ctx.agentVersionId(), ctx.agentToolConfig());
        List<String> allow = ctx.toolAllowList();
        if (allow == null || allow.isEmpty()) {
            return all;
        }
        return all.stream().filter(d -> allow.contains(d.name())).toList();
    }

    @Override
    public ToolProvider.ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args) {
        String name = toolName == null ? "" : toolName;
        Span span = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("tool.invoke")
                .setAttribute("tioka.tool", name)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
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
            result = ac.provider().invoke(ctx.agentVersionId(), name, ac.args(),
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
}

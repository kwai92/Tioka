package com.tioka.system.api;

import com.tioka.system.api.authz.SystemRoles;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.audit.AuditChainService;
import com.tioka.system.application.audit.AuditChainService.ChainVerifyResult;
import com.tioka.system.application.audit.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 审计查询与 CSV 导出（GOV-02）。
 *
 * <p>OBS-09 / ADR-072 §6：权限点细分为 {@code audit:read}（本空间）、{@code audit:read:all}（全局）
 * 与 {@code audit:export}（导出）；查询与导出本身都写审计（分类 {@code DATA}），导出另记录
 * 查询条件、行数与文件 SHA-256。分页改为按 {@code (ts, id)} 游标（响应头 {@code X-Next-Cursor}），
 * 查询条件增加 {@code trace_id}/{@code request_id}/{@code subject}/{@code turn_id}/{@code run_id}
 * 关联键（ADR-073 §1）。
 */
@RestController
@RequestMapping("/api/system/audit")
public class AuditController {
    private static final Duration MAX_RANGE = Duration.ofDays(93);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 100_000;
    private static final String HEADER_NEXT_CURSOR = "X-Next-Cursor";

    /** 审计自查询动作名（登记于 docs/contracts/audit/actions.yaml）。 */
    private static final String ACTION_QUERY = "audit.query";
    private static final String ACTION_EXPORT = "audit.export";

    private final AuditService auditService;
    private final AuditChainService auditChain;
    private final PrincipalHolder principals;
    private final AuditDegradedSink degraded;

    public AuditController(AuditService auditService, AuditChainService auditChain,
                           PrincipalHolder principals, AuditDegradedSink degraded) {
        this.auditService = auditService;
        this.auditChain = auditChain;
        this.principals = principals;
        this.degraded = degraded;
    }

    /**
     * 审计哈希链完整性校验（GOV-09）：重算指定链并比对封印锚。
     * 非全局权限者只能校验本 workspace 的链；系统级链（_system）仅全局权限者可见。
     */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/chain/verify")
    public ApiResponse<ChainVerifyResult> verifyChain(
            @RequestParam(required = false) String workspaceKey) {
        Principal p = principals.current();
        String key = workspaceKey;
        if (!globalAudit(p)) {
            if (AuditChainService.SYSTEM_CHAIN.equals(key)) {
                throw new BizException(ErrorCode.FORBIDDEN, "系统级审计链仅管理员可校验");
            }
            key = p.workspaceId();
        }
        return ApiResponse.ok(auditChain.verify(key));
    }

    /**
     * 审计查询（{@code audit:read}；跨空间需 {@code audit:read:all}）。
     *
     * <p>分页：按 {@code ts DESC, id DESC} 返回一页；若恰好取满 {@code size} 行，响应头
     * {@code X-Next-Cursor} 给出续查游标，把它作为下次请求的 {@code cursor} 参数即可。
     */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping
    public ApiResponse<List<AuditEvent>> query(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String turnId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            HttpServletResponse response) {
        Instant[] range = normalizeRange(from, to);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        AuditApi.AuditQuery q = buildQuery(range[0], range[1], actorId, action, resourceType, resourceId,
                outcome, traceId, requestId, subject, turnId, runId, cursor, safeSize);
        List<AuditEvent> rows = auditService.query(q);
        if (rows.size() == safeSize) {
            AuditEvent last = rows.get(rows.size() - 1);
            String next = new AuditApi.AuditCursor(last.ts(), last.id()).encode();
            if (next != null) {
                response.setHeader(HEADER_NEXT_CURSOR, next);
            }
        }
        writeAudit(ACTION_QUERY, q, rows.size(), null);
        return ApiResponse.ok(rows);
    }

    /**
     * 审计 CSV 导出（{@code audit:read} + {@code audit:export}）。
     *
     * <p>导出本身写审计，记录查询条件、导出行数与文件 SHA-256（ADR-072 §6）。
     */
    @RequirePermission({Permissions.AUDIT_READ, Permissions.AUDIT_EXPORT})
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String turnId,
            @RequestParam(required = false) String runId,
            HttpServletResponse response) throws IOException {
        Instant[] range = normalizeRange(from, to);
        AuditApi.AuditQuery q = buildQuery(range[0], range[1], actorId, action, resourceType, resourceId,
                outcome, traceId, requestId, subject, turnId, runId, null, MAX_EXPORT_ROWS);
        List<AuditEvent> rows = auditService.queryForExport(q, MAX_EXPORT_ROWS);

        StringBuilder sb = new StringBuilder(Math.max(256, rows.size() * 160));
        sb.append("id,ts,workspaceId,actorType,actorId,action,resourceType,resourceId,outcome,reason,")
                .append("traceId,spanId,service,requestId,turnId,runId,clientIp\n");
        for (AuditEvent e : rows) {
            sb.append(csv(e.id())).append(',').append(csv(e.ts())).append(',').append(csv(e.workspaceId()))
                    .append(',').append(csv(e.actorType())).append(',').append(csv(e.actorId()))
                    .append(',').append(csv(e.action())).append(',').append(csv(e.resourceType()))
                    .append(',').append(csv(e.resourceId()))
                    .append(',').append(csv(e.outcome() == null ? null : e.outcome().name()))
                    .append(',').append(csv(e.reason())).append(',').append(csv(e.traceId()))
                    .append(',').append(csv(e.spanId())).append(',').append(csv(e.service()))
                    .append(',').append(csv(e.requestId())).append(',').append(csv(e.turnId()))
                    .append(',').append(csv(e.runId())).append(',').append(csv(e.clientIp()))
                    .append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setContentLength(bytes.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit.csv\"");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();

        writeAudit(ACTION_EXPORT, q, rows.size(), sha256Hex(bytes));
    }

    private AuditApi.AuditQuery buildQuery(Instant from, Instant to, String actorId, String action,
                                           String resourceType, String resourceId, AuditOutcome outcome,
                                           String traceId, String requestId, String subject,
                                           String turnId, String runId, String cursor, int limit) {
        Principal p = principals.current();
        String workspaceId = globalAudit(p) ? null : p.workspaceId();
        return new AuditApi.AuditQuery(workspaceId, blankToNull(actorId), blankToNull(action),
                blankToNull(resourceType), blankToNull(resourceId), outcome, from, to,
                blankToNull(traceId), blankToNull(requestId), blankToNull(subject),
                blankToNull(turnId), blankToNull(runId), blankToNull(cursor), limit);
    }

    /** 跨空间审计权限：{@code audit:read:all} / {@code tenant:admin} / {@code *} / 系统管理员。 */
    private static boolean globalAudit(Principal p) {
        return p.hasPermission(Permissions.AUDIT_READ_ALL)
                || p.hasPermission(Permissions.TENANT_ADMIN)
                || p.hasPermission(Permissions.ALL)
                || p.roles().contains(SystemRoles.SYSTEM_ADMIN);
    }

    /** 查询 / 导出本身写审计（ADR-072 §6）：失败不阻断读路径，降级留痕。 */
    private void writeAudit(String action, AuditApi.AuditQuery q, int rowCount, String fileHash) {
        try {
            Principal p = principals.find().orElse(null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("conditions", conditions(q));
            details.put("rowCount", rowCount);
            if (fileHash != null) {
                details.put("fileHash", fileHash);
            }
            auditService.record(AuditEvent.builder()
                    .ts(Instant.now())
                    .workspaceId(q.workspaceId())
                    .actorType(p == null ? "system" : p.type().name().toLowerCase(Locale.ROOT))
                    .actorId(p == null ? "system" : p.userId())
                    .action(action)
                    .resourceType("audit_event")
                    .outcome(AuditOutcome.SUCCESS)
                    .details(details)
                    .build());
        } catch (RuntimeException ex) {
            try {
                degraded.append(AuditEvent.builder()
                        .ts(Instant.now())
                        .action(action)
                        .resourceType("audit_event")
                        .outcome(AuditOutcome.ERROR)
                        .reason(ex.getMessage())
                        .build());
            } catch (RuntimeException ignored) {
                // 降级留痕也失败时不再向上抛，避免拖垮读路径
            }
        }
    }

    private static Map<String, Object> conditions(AuditApi.AuditQuery q) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "workspaceId", q.workspaceId());
        put(c, "actorId", q.actorId());
        put(c, "action", q.action());
        put(c, "resourceType", q.resourceType());
        put(c, "resourceId", q.resourceId());
        put(c, "outcome", q.outcome() == null ? null : q.outcome().name());
        put(c, "traceId", q.traceId());
        put(c, "requestId", q.requestId());
        put(c, "subject", q.subject());
        put(c, "turnId", q.turnId());
        put(c, "runId", q.runId());
        put(c, "from", q.from());
        put(c, "to", q.to());
        return c;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static Instant[] normalizeRange(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (end.isBefore(start)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "to 必须不早于 from");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "查询时间跨度不能超过 93 天");
        }
        return new Instant[]{start, end};
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

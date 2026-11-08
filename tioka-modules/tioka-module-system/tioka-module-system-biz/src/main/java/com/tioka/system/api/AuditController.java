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
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.audit.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 审计查询与 CSV 导出（GOV-02）。
 */
@RestController
@RequestMapping("/api/system/audit")
public class AuditController {
    private static final Duration MAX_RANGE = Duration.ofDays(93);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 100_000;

    private final AuditService auditService;
    private final PrincipalHolder principals;

    public AuditController(AuditService auditService, PrincipalHolder principals) {
        this.auditService = auditService;
        this.principals = principals;
    }

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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Instant[] range = normalizeRange(from, to);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;
        AuditApi.AuditQuery q = buildQuery(range[0], range[1], actorId, action, resourceType, resourceId,
                outcome, String.valueOf(offset), safeSize);
        return ApiResponse.ok(auditService.query(q));
    }

    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) AuditOutcome outcome,
            HttpServletResponse response) throws IOException {
        Instant[] range = normalizeRange(from, to);
        AuditApi.AuditQuery q = buildQuery(range[0], range[1], actorId, action, resourceType, resourceId,
                outcome, null, MAX_EXPORT_ROWS);
        List<AuditEvent> rows = auditService.queryForExport(q, MAX_EXPORT_ROWS);
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit.csv\"");
        PrintWriter w = response.getWriter();
        w.println("id,ts,workspaceId,actorType,actorId,action,resourceType,resourceId,outcome,reason,traceId,requestId,clientIp");
        for (AuditEvent e : rows) {
            w.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    csv(e.id()), csv(e.ts()), csv(e.workspaceId()), csv(e.actorType()), csv(e.actorId()),
                    csv(e.action()), csv(e.resourceType()), csv(e.resourceId()),
                    csv(e.outcome() == null ? null : e.outcome().name()),
                    csv(e.reason()), csv(e.traceId()), csv(e.requestId()), csv(e.clientIp()));
        }
        w.flush();
    }

    private AuditApi.AuditQuery buildQuery(Instant from, Instant to, String actorId, String action,
                                           String resourceType, String resourceId, AuditOutcome outcome,
                                           String cursor, int limit) {
        Principal p = principals.current();
        String workspaceId = null;
        if (!p.hasPermission(Permissions.TENANT_ADMIN) && !p.hasPermission("*")
                && !p.roles().contains(SystemRoles.SYSTEM_ADMIN)) {
            workspaceId = p.workspaceId();
        }
        return new AuditApi.AuditQuery(workspaceId, actorId, action, resourceType, resourceId,
                outcome, from, to, cursor, limit);
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

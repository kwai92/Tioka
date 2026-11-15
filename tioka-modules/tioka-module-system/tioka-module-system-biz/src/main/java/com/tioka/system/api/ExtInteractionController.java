package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.authz.SystemRoles;
import com.tioka.system.api.obs.CounterpartyStat;
import com.tioka.system.api.obs.DestinationRow;
import com.tioka.system.api.obs.ExtInteractionQueryApi;
import com.tioka.system.api.obs.ExtInteractionView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 外部交互台账查询（OBS-07 / ADR-074 §4）。
 *
 * <p>台账是全局跨边界观测面（无租户列），因此查看限定平台级管理员，避免工作空间级用户跨端窥探。
 * 明细可按对方回执号反查（「输入对方单号，找到我方全过程」），也可按 {@code subject} 列出某份合同
 * 被发往过哪些外部系统与模型；另有数据去向报表与调用统计供合规检查。
 */
@RestController
@RequestMapping("/api/system/ext-interaction")
public class ExtInteractionController {
    private static final Duration MAX_RANGE = Duration.ofDays(93);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final ExtInteractionQueryApi ledger;
    private final PrincipalHolder principals;

    public ExtInteractionController(ExtInteractionQueryApi ledger, PrincipalHolder principals) {
        this.ledger = ledger;
        this.principals = principals;
    }

    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping
    public ApiResponse<List<ExtInteractionView>> query(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String counterparty,
            @RequestParam(required = false) String externalRef,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        requirePlatformAdmin();
        Instant[] range = normalizeRange(from, to);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (Math.max(page, 1) - 1) * safeSize;
        return ApiResponse.ok(ledger.query(new ExtInteractionQueryApi.Query(
                requestId, subject, counterparty, externalRef, kind, direction,
                range[0], range[1], safeSize, offset)));
    }

    /** 数据去向报表（ADR-074 §4）：哪些标签的数据去了哪些外部系统。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/destination")
    public ApiResponse<List<DestinationRow>> destination(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String dataLabel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requirePlatformAdmin();
        Instant[] range = normalizeRange(from, to);
        return ApiResponse.ok(ledger.destinationReport(kind, dataLabel, range[0], range[1]));
    }

    /** 调用统计（ADR-074 §4）：按对方与类别看调用量、失败率、P95 时延。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/stats")
    public ApiResponse<List<CounterpartyStat>> stats(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requirePlatformAdmin();
        Instant[] range = normalizeRange(from, to);
        return ApiResponse.ok(ledger.counterpartyStats(kind, range[0], range[1]));
    }

    /** 台账无租户列，仅平台级管理员（跨工作空间审计/合规角色）可查看。 */
    private void requirePlatformAdmin() {
        Principal p = principals.current();
        boolean admin = p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission(Permissions.ALL)
                || p.roles().contains(SystemRoles.SYSTEM_ADMIN);
        if (!admin) {
            throw new BizException(ErrorCode.FORBIDDEN, "外部交互台账为平台级观测面，仅管理员可查看");
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
}

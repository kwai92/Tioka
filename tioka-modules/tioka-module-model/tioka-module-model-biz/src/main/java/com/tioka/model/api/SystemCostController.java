package com.tioka.model.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.model.application.CostQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * GOV-05：系统成本归集（汇总 / 趋势）。
 */
@RestController
@RequestMapping("/api/system/cost")
public class SystemCostController {
    private final CostQueryService costs;
    private final PrincipalHolder principals;

    public SystemCostController(CostQueryService costs, PrincipalHolder principals) {
        this.costs = costs;
        this.principals = principals;
    }

    @RequirePermission(Permissions.QUOTA_READ)
    @GetMapping("/summary")
    public ApiResponse<List<CostQueryApi.CostBucket>> summary(
            @RequestParam String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant[] range = normalizeRange(from, to);
        return ApiResponse.ok(costs.summary(groupBy, range[0], range[1], workspaceFilter()));
    }

    @RequirePermission(Permissions.QUOTA_READ)
    @GetMapping("/trend")
    public ApiResponse<List<CostQueryApi.CostPoint>> trend(
            @RequestParam String scopeType,
            @RequestParam String scopeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "day") String interval) {
        Instant[] range = normalizeRange(from, to);
        String effectiveScopeId = scopeId;
        String filter = workspaceFilter();
        if (filter != null && "workspace".equalsIgnoreCase(scopeType) && !filter.equals(scopeId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "非系统管理员只能查看当前空间成本");
        }
        return ApiResponse.ok(costs.trend(scopeType, effectiveScopeId, range[0], range[1], interval, filter));
    }

    /**
     * 非系统管理员强制限定当前空间；系统管理员（system_admin / *）可跨空间。
     */
    private String workspaceFilter() {
        Principal p = principals.current();
        if (p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission(Permissions.ALL)
                || p.roles().contains("system_admin")) {
            return null;
        }
        String ws = p.workspaceId();
        if (ws == null || ws.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少当前空间");
        }
        return ws;
    }

    /** 缺省：本月月初（系统时区）至现在。 */
    private static Instant[] normalizeRange(Instant from, Instant to) {
        ZoneId zone = ZoneId.systemDefault();
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null
                ? LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant()
                : from;
        if (end.isBefore(start)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "to 必须不早于 from");
        }
        return new Instant[]{start, end};
    }
}

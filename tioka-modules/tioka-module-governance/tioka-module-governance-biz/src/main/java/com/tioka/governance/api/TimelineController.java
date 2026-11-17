package com.tioka.governance.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineEntryView;
import com.tioka.governance.api.timeline.TimelinePageSpec;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.system.api.audit.Audited;
import com.tioka.system.api.authz.SystemRoles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一时间线查询与重建（OBS-08 / ADR-075 §4）。
 *
 * <p>两种视角：请求时间线（{@code request_id}，含派生请求由 {@code parent_request_id} 展开）与
 * 主体时间线（{@code subject}，如一份合同）。投影只读，下钻由调用方按条目的
 * {@code sourceType} / {@code sourceId} 取原记录。
 *
 * <p>权限过滤三层：数据标签、空间、{@code trajectory:read:content}。
 * 平台级管理员（{@code tenant:admin} / {@code system_admin} / {@code *}）不做标签与空间过滤，
 * 可传 {@code workspaceId} 指定空间；其余调用方空间强制取自身空间，且只有持
 * {@code trajectory:read:content} 才可读到 {@code CONFIDENTIAL} 及以上的条目，否则上限为
 * {@code INTERNAL}。
 */
@RestController
@RequestMapping("/api/governance/timeline")
public class TimelineController {
    /** 显式指定区间时的最大跨度；不传区间时按 {@code request_id} / {@code subject} 索引收敛。 */
    private static final Duration MAX_RANGE = Duration.ofDays(93);

    private final TimelineQuery timeline;
    private final PrincipalHolder principals;

    public TimelineController(TimelineQuery timeline, PrincipalHolder principals) {
        this.timeline = timeline;
        this.principals = principals;
    }

    /** 请求时间线：按 {@code requestId} 游标分页取条目。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/request")
    public ApiResponse<List<TimelineEntryView>> request(
            @RequestParam String requestId,
            @RequestParam(required = false) List<TimelineCategory> categories,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TimelinePageSpec.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String workspaceId) {
        TimelinePageSpec spec = spec(categories, from, to, cursor, limit, workspaceId);
        return ApiResponse.ok(timeline.pageByRequest(requestId, spec));
    }

    /** 主体时间线：按 {@code subjectType} + {@code subjectId} 游标分页取条目。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/subject")
    public ApiResponse<List<TimelineEntryView>> subject(
            @RequestParam String subjectType,
            @RequestParam String subjectId,
            @RequestParam(required = false) List<TimelineCategory> categories,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TimelinePageSpec.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String workspaceId) {
        TimelinePageSpec spec = spec(categories, from, to, cursor, limit, workspaceId);
        return ApiResponse.ok(timeline.pageBySubject(subjectType, subjectId, spec));
    }

    /** 按时间段重建投影（ADR-075 §2）；运维动作，限平台级管理员。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @PostMapping("/rebuild")
    @Audited(action = "timeline.rebuild", resourceType = "timeline", include = {"from", "to"})
    public ApiResponse<Integer> rebuild(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requirePlatformAdmin();
        Instant[] range = normalizeRange(from, to);
        return ApiResponse.ok(timeline.rebuild(range[0], range[1]));
    }

    private TimelinePageSpec spec(List<TimelineCategory> categories, Instant from, Instant to,
                                  String cursor, int limit, String workspaceId) {
        Instant[] range = normalizeRange(from, to);
        Principal p = principals.current();
        boolean platform = isPlatformAdmin(p);
        String ws = platform ? workspaceId : p.workspaceId();
        DataLabel clearance = platform || p.hasPermission(Permissions.TRAJECTORY_READ_CONTENT)
                ? DataLabel.SECRET : DataLabel.INTERNAL;
        Set<TimelineCategory> cats = categories == null ? Set.of() : new LinkedHashSet<>(categories);
        return new TimelinePageSpec(cats, range[0], range[1], cursor, limit, clearance, ws);
    }

    private static boolean isPlatformAdmin(Principal p) {
        return p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission(Permissions.ALL)
                || p.roles().contains(SystemRoles.SYSTEM_ADMIN);
    }

    private void requirePlatformAdmin() {
        if (!isPlatformAdmin(principals.current())) {
            throw new BizException(ErrorCode.FORBIDDEN, "时间线重建为平台级运维动作，仅管理员可执行");
        }
    }

    private static Instant[] normalizeRange(Instant from, Instant to) {
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "to 必须不早于 from");
            }
            if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "查询时间跨度不能超过 93 天");
            }
        }
        return new Instant[]{from, to};
    }
}

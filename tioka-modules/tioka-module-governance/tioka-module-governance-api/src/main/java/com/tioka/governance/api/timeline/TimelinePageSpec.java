package com.tioka.governance.api.timeline;

import com.tioka.framework.label.DataLabel;

import java.time.Instant;
import java.util.Set;

/**
 * 时间线分页条件（ADR-075 §4）：按类别过滤、按数据标签权限过滤、按空间过滤，游标分页。
 *
 * @param categories 类别过滤；空集表示不过滤
 * @param from       起始时间（含）；{@code null} 不限
 * @param to         结束时间（含）；{@code null} 不限
 * @param cursor     游标（{@code epochMilli}:{@code id}），来自上一页最后一条；{@code null} 表示第一页
 * @param limit      每页条数，1..200，缺省 50
 * @param clearance  调用方可读的最高数据标签；{@code null} 表示不做标签过滤（仅平台级调用方）
 * @param workspaceId 空间过滤；{@code null} 表示不限（平台级调用方）
 */
public record TimelinePageSpec(
        Set<TimelineCategory> categories,
        Instant from,
        Instant to,
        String cursor,
        int limit,
        DataLabel clearance,
        String workspaceId) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public TimelinePageSpec {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    public static TimelinePageSpec firstPage() {
        return new TimelinePageSpec(Set.of(), null, null, null, DEFAULT_LIMIT, null, null);
    }
}

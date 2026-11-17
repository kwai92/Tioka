package com.tioka.governance.infrastructure.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 时间线分页查询参数（infrastructure 内部）：由 {@code TimelinePageSpec}（API）解析而来，
 * 携带游标 {(@code occurred_at}, {@code id})、类别与空间。
 *
 * <p>数据标签不参与此层过滤：条目始终返回，超限正文由 {@code DefaultTimelineQuery} 按
 * 调用者 clearance 抹除（ADR-075 §4 / FE-00 §7.10）。
 *
 * <p>普通类（非 record）：MyBatis 反射取属性依赖 {@code getXxx} 访问器。
 */
public class TimelinePageQuery {

    private final Instant from;
    private final Instant to;
    private final Instant cursorTs;
    private final String cursorId;
    private final int limit;
    private final List<String> categories;
    private final String workspaceId;

    public TimelinePageQuery(Instant from, Instant to, Instant cursorTs, String cursorId, int limit,
                            List<String> categories, String workspaceId) {
        this.from = from;
        this.to = to;
        this.cursorTs = cursorTs;
        this.cursorId = cursorId;
        this.limit = limit;
        this.categories = categories;
        this.workspaceId = workspaceId;
    }

    public Instant getFrom() { return from; }
    public Instant getTo() { return to; }
    public Instant getCursorTs() { return cursorTs; }
    public String getCursorId() { return cursorId; }
    public int getLimit() { return limit; }
    public List<String> getCategories() { return categories; }
    public String getWorkspaceId() { return workspaceId; }
}

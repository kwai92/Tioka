package com.tioka.governance.api.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 统一时间线查询出口（OBS-08 / ADR-075 §4）：按 {@code request_id}（请求时间线）或
 * {@code subject}（主体时间线）游标分页取条目，按类别过滤，并按权限过滤（{@code data_label} 与空间）。
 *
 * <p>投影是只读视图：下钻到来源记录（审计、外部交互、轨迹、审批等）由调用方按
 * {@link TimelineEntryView#sourceType()} / {@link #sourceId()} 自行取。
 */
public interface TimelineQuery {

    /**
     * 请求时间线：按 {@code request_id} 取条目（含派生请求需调用方按 {@code parent_request_id} 展开）。
     */
    List<TimelineEntryView> pageByRequest(String requestId, TimelinePageSpec spec);

    /**
     * 主体时间线：按主体类型与标识取条目（如一份合同）。
     */
    List<TimelineEntryView> pageBySubject(String subjectType, String subjectId, TimelinePageSpec spec);

    /**
     * 按时间段重建投影（ADR-075 §2「可按时间段重建」）：先按来源清除区间内条目，再由各
     * {@link TimelineRebuildSource} 提供区间内来源事件重新投影。
     *
     * @return 实际重建的条目数
     */
    int rebuild(Instant from, Instant to);
}

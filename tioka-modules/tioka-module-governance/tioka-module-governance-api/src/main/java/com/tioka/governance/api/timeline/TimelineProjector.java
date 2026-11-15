package com.tioka.governance.api.timeline;

/**
 * 时间线投影入口（OBS-08 / ADR-075 §2）。各域在既有写入点或领域事件处调用，把来源事件投影为
 * {@code timeline_entry}。幂等：同一 {@code sourceEventId} 只落一条。
 *
 * <p>失败静默降级（投影不是事实源，不得影响主流程）。
 */
public interface TimelineProjector {

    /**
     * 投影一条来源事件；返回条目 id。重复（{@code sourceEventId} 已存在）时返回已存在条目的 id。
     */
    String project(TimelineSourceEvent event);
}

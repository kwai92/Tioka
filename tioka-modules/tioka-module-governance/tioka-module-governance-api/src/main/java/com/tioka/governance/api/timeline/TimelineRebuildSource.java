package com.tioka.governance.api.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 时间线重建来源 SPI（OBS-08 / ADR-075 §2「可按时间段重建」）。每个来源类型（如 {@code ext_interaction}、
 * {@code audit_event}、{@code runtime_item}）实现一个，供 {@link TimelineQuery#rebuild(Instant, Instant)} 重建。
 *
 * <p>由实现模块装配为 Spring Bean；缺失时该来源不参与重建（不报错）。
 */
public interface TimelineRebuildSource {

    /** 来源类型标识，与 {@code timeline_entry.source_type} 同口径。 */
    String sourceType();

    /** 区间内的来源事件（按 {@code occurredAt} 升序）。 */
    List<TimelineSourceEvent> eventsInRange(Instant from, Instant to);
}

package com.tioka.governance.application.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.infrastructure.config.CfgChangeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code cfg_change} 的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内的配置变更，
 * 按 {@link CfgChangeTimelineProjection} 重新投影 {@code CONFIG} 背景条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 * 经 {@link CfgChangeRepository} 读库（application 不直持 Mapper）。
 */
public class CfgChangeTimelineRebuildSource implements TimelineRebuildSource {

    private final CfgChangeRepository changes;
    private final ObjectMapper mapper;

    public CfgChangeTimelineRebuildSource(CfgChangeRepository changes, ObjectMapper mapper) {
        this.changes = changes;
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return CfgChangeTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : changes.listByRange(from, to)) {
            TimelineSourceEvent event = CfgChangeTimelineProjection.toSourceEvent(row, mapper);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

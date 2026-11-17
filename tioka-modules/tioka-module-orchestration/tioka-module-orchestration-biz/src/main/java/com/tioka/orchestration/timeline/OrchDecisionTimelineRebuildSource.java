package com.tioka.orchestration.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code orch_decision} 的时间线重建来源（OBS-08 / ADR-075 §2、§6）：读回区间内的规则求值，
 * 按 {@link OrchDecisionTimelineProjection} 重新投影 {@code DECISION} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class OrchDecisionTimelineRebuildSource implements TimelineRebuildSource {

    private final OrchEngineRepository repository;

    public OrchDecisionTimelineRebuildSource(OrchEngineRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() {
        return OrchDecisionTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : repository.listDecisionsByRange(from, to)) {
            TimelineSourceEvent event = OrchDecisionTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

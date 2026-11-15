package com.tioka.model.application.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.model.infrastructure.ModelUsageRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code model_usage} 的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内的模型用量，
 * 按 {@link ModelUsageTimelineProjection} 重新投影 {@code MODEL} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class ModelUsageTimelineRebuildSource implements TimelineRebuildSource {

    private final ModelUsageRepository repository;

    public ModelUsageTimelineRebuildSource(ModelUsageRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() {
        return ModelUsageTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        return repository.listByRange(from, to).stream()
                .map(ModelUsageTimelineProjection::toSourceEvent)
                .filter(Objects::nonNull)
                .toList();
    }
}

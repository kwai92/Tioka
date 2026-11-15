package com.tioka.governance.application.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.infrastructure.manifest.RunManifestRepository;

import java.time.Instant;
import java.util.List;

/**
 * 运行清单的时间线重建来源（OBS-08 / ADR-075 §2「可按时间段重建」）：
 * 读回区间内的 {@code run_manifest}，按 {@link RunManifestTimelineProjection} 重新投影 {@code RUN} 条目。
 */
public class RunManifestTimelineRebuildSource implements TimelineRebuildSource {

    private final RunManifestRepository repository;

    public RunManifestTimelineRebuildSource(RunManifestRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() {
        return RunManifestTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        return repository.listByRange(from, to).stream()
                .map(RunManifestTimelineProjection::toSourceEvent)
                .toList();
    }
}

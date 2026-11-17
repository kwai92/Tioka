package com.tioka.governance.application.timeline;

import com.tioka.framework.label.DataLabel;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineEntryView;
import com.tioka.governance.api.timeline.TimelineOutcome;
import com.tioka.governance.api.timeline.TimelinePageSpec;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.api.timeline.TimelineSubjectRef;
import com.tioka.governance.infrastructure.persistence.TimelineEntryDO;
import com.tioka.governance.infrastructure.persistence.TimelineEntrySubjectDO;
import com.tioka.governance.infrastructure.persistence.TimelineProjectionCursorDO;
import com.tioka.governance.infrastructure.timeline.TimelinePageQuery;
import com.tioka.governance.infrastructure.timeline.TimelineRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一时间线查询与重建（OBS-08 / ADR-075 §4）：
 * 请求/主体两条视角的游标分页（按 {@code (occurred_at, id)}）、类别过滤、空间权限过滤；
 * 数据标签不隐藏条目，仅在超限时抹除正文（FE-00 §7.10「只显示标题」）。
 */
public class DefaultTimelineQuery implements TimelineQuery {

    private final TimelineRepository repository;
    private final TimelineProjector projector;
    private final List<TimelineRebuildSource> rebuildSources;

    public DefaultTimelineQuery(TimelineRepository repository,
                                TimelineProjector projector,
                                List<TimelineRebuildSource> rebuildSources) {
        this.repository = repository;
        this.projector = projector;
        this.rebuildSources = rebuildSources == null ? List.of() : rebuildSources;
    }

    @Override
    public List<TimelineEntryView> pageByRequest(String requestId, TimelinePageSpec spec) {
        if (requestId == null || requestId.isBlank()) {
            return List.of();
        }
        return toViews(repository.pageByRequest(requestId, toQuery(spec)), clearance(spec));
    }

    @Override
    public List<TimelineEntryView> pageBySubject(String subjectType, String subjectId, TimelinePageSpec spec) {
        if (subjectType == null || subjectType.isBlank() || subjectId == null || subjectId.isBlank()) {
            return List.of();
        }
        return toViews(repository.pageBySubject(subjectType, subjectId, toQuery(spec)), clearance(spec));
    }

    @Override
    public int rebuild(Instant from, Instant to) {
        int total = 0;
        for (TimelineRebuildSource source : rebuildSources) {
            String sourceType = source.sourceType();
            repository.deleteBySourceRange(sourceType, from, to);
            List<TimelineSourceEvent> events = source.eventsInRange(from, to);
            if (events == null) {
                continue;
            }
            TimelineSourceEvent last = null;
            for (TimelineSourceEvent event : events) {
                if (projector.project(event) != null) {
                    total++;
                    last = event;
                }
            }
            if (last != null) {
                TimelineProjectionCursorDO cursor = new TimelineProjectionCursorDO();
                cursor.setSourceType(sourceType);
                cursor.setCursorTs(last.occurredAt());
                cursor.setCursorId(last.sourceEventId());
                cursor.setLastEventId(last.sourceEventId());
                repository.upsertCursor(cursor);
            }
        }
        return total;
    }

    private static TimelinePageQuery toQuery(TimelinePageSpec spec) {
        TimelinePageSpec s = spec == null ? TimelinePageSpec.firstPage() : spec;
        Instant cursorTs = null;
        String cursorId = null;
        String cursor = s.cursor();
        if (cursor != null && !cursor.isBlank()) {
            int idx = cursor.indexOf(':');
            if (idx > 0) {
                try {
                    cursorTs = Instant.ofEpochMilli(Long.parseLong(cursor.substring(0, idx)));
                    cursorId = cursor.substring(idx + 1);
                } catch (NumberFormatException ignored) {
                    cursorTs = null;
                    cursorId = null;
                }
            }
        }
        List<String> categories = s.categories().stream().map(TimelineCategory::name).toList();
        return new TimelinePageQuery(s.from(), s.to(), cursorTs, cursorId, s.limit(),
                categories, s.workspaceId());
    }

    private static DataLabel clearance(TimelinePageSpec spec) {
        return spec == null ? null : spec.clearance();
    }

    private List<TimelineEntryView> toViews(List<TimelineEntryDO> rows, DataLabel clearance) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(TimelineEntryDO::getId).toList();
        Map<String, List<TimelineSubjectRef>> subjects = new LinkedHashMap<>();
        for (TimelineEntrySubjectDO link : repository.listSubjects(ids)) {
            subjects.computeIfAbsent(link.getEntryId(), k -> new ArrayList<>())
                    .add(new TimelineSubjectRef(link.getSubjectType(), link.getSubjectId()));
        }
        return rows.stream().map(row -> toView(row,
                subjects.getOrDefault(row.getId(), List.of()), clearance)).toList();
    }

    private static TimelineEntryView toView(TimelineEntryDO row, List<TimelineSubjectRef> subjects,
                                            DataLabel clearance) {
        return new TimelineEntryView(
                row.getId(),
                row.getOccurredAt(),
                row.getSeq() == null ? 0 : row.getSeq(),
                row.getRequestId(),
                row.getParentRequestId(),
                row.getCategory() == null ? null : TimelineCategory.valueOf(row.getCategory()),
                row.getTitle(),
                visibleSummary(row.getDataLabel(), row.getSummary(), clearance),
                row.getActor(),
                TimelineOutcome.parse(row.getOutcome()),
                row.getSourceType(),
                row.getSourceId(),
                row.getTraceId(),
                row.getWorkspaceId(),
                row.getDataLabel(),
                subjects);
    }

    /**
     * 数据标签过滤（ADR-075 §4）：条目本身不因标签而隐藏，但正文（summary）序高于调用者
     * {@code clearance} 时抹除——即 FE-00 §7.10「无 trajectory:read:content 时 CONFIDENTIAL 条目只显示标题」。
     * {@code clearance == null} 表示不限制。
     */
    private static String visibleSummary(String dataLabel, String summary, DataLabel clearance) {
        if (summary == null || clearance == null) {
            return summary;
        }
        DataLabel own = parseLabel(dataLabel);
        return own == null || own.atMost(clearance) ? summary : null;
    }

    private static DataLabel parseLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DataLabel l : DataLabel.values()) {
            if (l.name().equalsIgnoreCase(raw)) {
                return l;
            }
        }
        return null;
    }
}

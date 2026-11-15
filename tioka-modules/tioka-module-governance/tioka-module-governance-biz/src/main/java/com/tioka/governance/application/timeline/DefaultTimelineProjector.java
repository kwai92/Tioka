package com.tioka.governance.application.timeline;

import com.foundation.common.id.Ids;
import com.tioka.framework.label.DataLabel;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineOutcome;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.governance.infrastructure.persistence.TimelineEntryDO;
import com.tioka.governance.infrastructure.persistence.TimelineEntrySubjectDO;
import com.tioka.governance.infrastructure.timeline.TimelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 时间线投影（OBS-08 / ADR-075 §2）：把来源事件写成 {@code timeline_entry}（+ 主体关联）。
 *
 * <p>幂等：按 {@code sourceEventId} 去重（命中返回已存在条目 id）。失败静默降级——
 * 投影不是事实源，不得影响主流程（与 {@code ExtInteractionRecorder} 同口径）。
 */
public class DefaultTimelineProjector implements TimelineProjector {

    private static final Logger log = LoggerFactory.getLogger(DefaultTimelineProjector.class);

    private static final String ID_PREFIX = "tle";

    private final TimelineRepository repository;

    public DefaultTimelineProjector(TimelineRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public String project(TimelineSourceEvent event) {
        if (event == null) {
            return null;
        }
        try {
            Map<String, Object> attrs = event.attrs() == null ? Map.of() : event.attrs();
            TimelineCategory category = category(attrs.get("category"));
            if (category == null) {
                log.warn("timeline projection skipped: missing/invalid category eventType={} eventId={}",
                        event.eventType(), event.sourceEventId());
                return null;
            }
            if (event.sourceEventId() != null) {
                String existing = repository.findIdBySourceEvent(event.sourceEventId());
                if (existing != null) {
                    return existing;
                }
            }

            TimelineEntryDO row = new TimelineEntryDO();
            row.setId(Ids.newId(ID_PREFIX));
            row.setOccurredAt(event.occurredAt() == null ? Instant.now() : event.occurredAt());
            row.setSeq(intOrZero(attrs.get("seq")));
            row.setRequestId(firstNonBlank(event.requestId(), str(attrs.get("requestId"))));
            row.setParentRequestId(firstNonBlank(event.parentRequestId(), str(attrs.get("parentRequestId"))));
            row.setCategory(category.name());
            row.setTitle(truncate(str(attrs.get("title")), 512));
            row.setSummary(str(attrs.get("summary")));
            row.setActor(str(attrs.get("actor")));
            TimelineOutcome outcome = TimelineOutcome.parse(str(attrs.get("outcome")));
            row.setOutcome(outcome == null ? null : outcome.name());
            row.setSourceType(firstNonBlank(str(attrs.get("sourceType")), event.eventType()));
            row.setSourceId(str(attrs.get("sourceId")));
            row.setTraceId(str(attrs.get("traceId")));
            row.setWorkspaceId(event.workspaceId());
            row.setDataLabel(label(attrs.get("dataLabel")));
            row.setSourceEventId(event.sourceEventId());

            repository.insertEntry(row);

            if (event.subjectType() != null && event.subjectId() != null) {
                TimelineEntrySubjectDO link = new TimelineEntrySubjectDO();
                link.setEntryId(row.getId());
                link.setSubjectType(event.subjectType());
                link.setSubjectId(event.subjectId());
                link.setWorkspaceId(event.workspaceId());
                repository.insertSubject(link);
            }
            return row.getId();
        } catch (RuntimeException ex) {
            log.warn("timeline projection failed eventType={} eventId={}: {}",
                    event.eventType(), event.sourceEventId(), ex.toString());
            return null;
        }
    }

    private static TimelineCategory category(Object raw) {
        String s = str(raw);
        if (s == null) {
            return null;
        }
        for (TimelineCategory c : TimelineCategory.values()) {
            if (c.name().equalsIgnoreCase(s)) {
                return c;
            }
        }
        return null;
    }

    private static String label(Object raw) {
        String s = str(raw);
        if (s == null) {
            return null;
        }
        for (DataLabel l : DataLabel.values()) {
            if (l.name().equalsIgnoreCase(s)) {
                return l.name();
            }
        }
        return null;
    }

    private static int intOrZero(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String s = str(raw);
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String str(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

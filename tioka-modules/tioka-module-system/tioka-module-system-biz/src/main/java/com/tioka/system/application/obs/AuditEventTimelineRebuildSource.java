package com.tioka.system.application.obs;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计事件的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内 {@code category: SECURITY} 的
 * 审计动作，按 {@link AuditEventTimelineProjection} 重新投影 {@code SECURITY} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class AuditEventTimelineRebuildSource implements TimelineRebuildSource {

    private final AuditEventRepository audit;

    public AuditEventTimelineRebuildSource(AuditEventRepository audit) {
        this.audit = audit;
    }

    @Override
    public String sourceType() {
        return AuditEventTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : audit.listByActionsInRange(List.copyOf(SecurityAuditActions.actions()), from, to)) {
            TimelineSourceEvent event = AuditEventTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

package com.tioka.runtime.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code runtime_approval} 的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内的审批单，
 * 按 {@link RuntimeApprovalTimelineProjection} 重新投影 {@code APPROVAL} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class RuntimeApprovalTimelineRebuildSource implements TimelineRebuildSource {

    private final ApprovalRepository approvals;

    public RuntimeApprovalTimelineRebuildSource(ApprovalRepository approvals) {
        this.approvals = approvals;
    }

    @Override
    public String sourceType() {
        return RuntimeApprovalTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : approvals.listByRange(from, to)) {
            TimelineSourceEvent event = RuntimeApprovalTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

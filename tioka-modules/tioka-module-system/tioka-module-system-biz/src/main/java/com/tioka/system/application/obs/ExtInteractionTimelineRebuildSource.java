package com.tioka.system.application.obs;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.api.obs.ExtInteractionQueryApi;
import com.tioka.system.api.obs.ExtInteractionView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部交互台账的时间线重建来源（OBS-08 / ADR-075 §2「可按时间段重建」）：
 * 读回区间内的 {@code ext_interaction}，按 {@link ExtInteractionTimelineProjection} 重新投影
 * {@code INBOUND}/{@code MODEL}/{@code TOOL}/{@code NOTIFY}/{@code OUTBOUND} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class ExtInteractionTimelineRebuildSource implements TimelineRebuildSource {

    /** 分页读回区间台账，避免一次拉全表。 */
    private static final int PAGE = 500;

    private final ExtInteractionQueryApi queryApi;

    public ExtInteractionTimelineRebuildSource(ExtInteractionQueryApi queryApi) {
        this.queryApi = queryApi;
    }

    @Override
    public String sourceType() {
        return ExtInteractionTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<ExtInteractionView> batch = queryApi.query(new ExtInteractionQueryApi.Query(
                    null, null, null, null, null, null, from, to, PAGE, offset));
            for (ExtInteractionView view : batch) {
                TimelineSourceEvent event = ExtInteractionTimelineProjection.toSourceEvent(view);
                if (event != null) {
                    out.add(event);
                }
            }
            if (batch.size() < PAGE) {
                break;
            }
            offset += PAGE;
        }
        return out;
    }
}

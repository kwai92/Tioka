package com.tioka.eval.timeline;

import com.tioka.eval.infrastructure.EvalFeedbackRepository;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code eval_feedback} 的时间线重建来源（OBS-08 / ADR-075 §2「反馈项」）：读回区间内的反馈，
 * 按 {@link EvalFeedbackTimelineProjection} 重新投影 {@code FEEDBACK} 条目。
 *
 * <p>与 {@code EvalFeedbackRepository} 同条件装配（{@code tioka.eval.enabled=true}）：
 * 评测模块关闭时不注册，避免依赖条件 bean 导致启动失败（C-8 教训）。由
 * {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalFeedbackTimelineRebuildSource implements TimelineRebuildSource {

    private final EvalFeedbackRepository repository;

    public EvalFeedbackTimelineRebuildSource(EvalFeedbackRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() {
        return EvalFeedbackTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : repository.listByRange(from, to)) {
            TimelineSourceEvent event = EvalFeedbackTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

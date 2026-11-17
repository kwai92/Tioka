package com.tioka.runtime.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.infrastructure.TrustActionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code trust_action} 的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内的变更动作，
 * 按 {@link TrustActionTimelineProjection} 重新投影 {@code CHANGE} 条目（变更单提交 / 撤销补偿）。
 *
 * <p>关联键（{@code request_id}/{@code subject}）在 {@code runtime_turn} 上，故按 {@code turnId} 解析补齐。
 * 由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class TrustActionTimelineRebuildSource implements TimelineRebuildSource {

    private final TrustActionRepository actions;
    private final TurnRepository turns;

    public TrustActionTimelineRebuildSource(TrustActionRepository actions, TurnRepository turns) {
        this.actions = actions;
        this.turns = turns;
    }

    @Override
    public String sourceType() {
        return TrustActionTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        Map<String, RuntimeTurnDO> cache = new HashMap<>();
        Function<String, RuntimeTurnDO> turnResolver =
                turnId -> cache.computeIfAbsent(turnId, id -> turns.findById(id).orElse(null));
        for (var row : actions.listByRange(from, to)) {
            TimelineSourceEvent event = TrustActionTimelineProjection.toSourceEvent(row, turnResolver);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

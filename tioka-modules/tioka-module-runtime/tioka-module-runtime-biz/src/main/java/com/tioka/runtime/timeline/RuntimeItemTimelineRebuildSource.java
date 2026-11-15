package com.tioka.runtime.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code runtime_item} 的时间线重建来源（OBS-08 / ADR-075 §2）：读回区间内的运行项，
 * 按 {@link RuntimeItemTimelineProjection} 重新投影 {@code TOOL} 条目（按步合并）。
 *
 * <p>关联键（{@code request_id}/{@code subject}）在 {@code runtime_turn} 上，故按 {@code turnId} 解析补齐。
 * 由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
 */
@Component
public class RuntimeItemTimelineRebuildSource implements TimelineRebuildSource {

    private final ItemRepository items;
    private final TurnRepository turns;
    private final RuntimeItemTimelineProjection projection;

    public RuntimeItemTimelineRebuildSource(ItemRepository items, TurnRepository turns,
                                            ObjectMapper mapper) {
        this.items = items;
        this.turns = turns;
        this.projection = new RuntimeItemTimelineProjection(mapper);
    }

    @Override
    public String sourceType() {
        return RuntimeItemTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<RuntimeItemDO> rows = items.listByRange(from, to);
        Map<String, RuntimeTurnDO> cache = new HashMap<>();
        Function<String, RuntimeTurnDO> turnResolver =
                turnId -> cache.computeIfAbsent(turnId, id -> turns.findById(id).orElse(null));
        return projection.toSourceEvents(rows, turnResolver);
    }
}

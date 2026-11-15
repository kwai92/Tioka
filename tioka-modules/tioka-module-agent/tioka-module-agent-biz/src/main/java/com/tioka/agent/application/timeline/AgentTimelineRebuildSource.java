package com.tioka.agent.application.timeline;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code agent_version} 的时间线重建来源（OBS-12 / ADR-075 §4 Agent 主体时间线）：
 * 读回区间内的版本，按 {@link AgentTimelineProjection} 重新投影 {@code CONFIG} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集；
 * agent 的自动配置为无条件组件扫描，故 {@code @Component} 即可。经 {@link AgentRepository} 读库
 * （application 不直持 Mapper）。
 */
@Component
public class AgentTimelineRebuildSource implements TimelineRebuildSource {

    private final AgentRepository agents;

    public AgentTimelineRebuildSource(AgentRepository agents) {
        this.agents = agents;
    }

    @Override
    public String sourceType() {
        return AgentTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : agents.listByRange(from, to)) {
            TimelineSourceEvent event = AgentTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

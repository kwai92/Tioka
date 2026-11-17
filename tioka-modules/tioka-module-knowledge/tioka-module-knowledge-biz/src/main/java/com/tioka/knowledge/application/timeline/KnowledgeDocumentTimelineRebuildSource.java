package com.tioka.knowledge.application.timeline;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code kb_document} 的时间线重建来源（OBS-12 / ADR-075 §4 知识文档主体时间线）：
 * 读回区间内的文档，按 {@link KnowledgeDocumentTimelineProjection} 重新投影 {@code CONFIG} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集；
 * knowledge 的自动配置为无条件组件扫描，故 {@code @Component} 即可（无需显式 {@code @Bean}）。
 * 经 {@link KnowledgeRepository} 读库（application 不直持 Mapper）。
 */
@Component
public class KnowledgeDocumentTimelineRebuildSource implements TimelineRebuildSource {

    private final KnowledgeRepository documents;

    public KnowledgeDocumentTimelineRebuildSource(KnowledgeRepository documents) {
        this.documents = documents;
    }

    @Override
    public String sourceType() {
        return KnowledgeDocumentTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : documents.listByRange(from, to)) {
            TimelineSourceEvent event = KnowledgeDocumentTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}

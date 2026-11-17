package com.tioka.governance.infrastructure.timeline;

import com.tioka.governance.infrastructure.mapper.TimelineEntryMapper;
import com.tioka.governance.infrastructure.persistence.TimelineEntryDO;
import com.tioka.governance.infrastructure.persistence.TimelineEntrySubjectDO;
import com.tioka.governance.infrastructure.persistence.TimelineProjectionCursorDO;

import java.time.Instant;
import java.util.List;

/**
 * 时间线投影读写（OBS-08 / ADR-075 §2）。
 * <p>由 {@code TimelineAutoConfiguration} 装配（不经全局 ComponentScan）。只增不改（重建时按来源区间清除后重放）；
 * 空间与数据标签隔离在服务层显式过滤（Mapper 旁路 ScopeLine）。
 */
public class TimelineRepository {

    private final TimelineEntryMapper mapper;

    public TimelineRepository(TimelineEntryMapper mapper) {
        this.mapper = mapper;
    }

    /** 幂等键查询：同一来源事件已投影的条目 id，无则 {@code null}。 */
    public String findIdBySourceEvent(String sourceEventId) {
        return sourceEventId == null ? null : mapper.findIdBySourceEvent(sourceEventId);
    }

    public void insertEntry(TimelineEntryDO row) {
        mapper.insertEntry(row);
    }

    public void insertSubject(TimelineEntrySubjectDO row) {
        mapper.insertSubject(row);
    }

    public List<TimelineEntryDO> pageByRequest(String requestId, TimelinePageQuery query) {
        return mapper.pageByRequest(requestId, query);
    }

    public List<TimelineEntryDO> pageBySubject(String subjectType, String subjectId, TimelinePageQuery query) {
        return mapper.pageBySubject(subjectType, subjectId, query);
    }

    public List<TimelineEntrySubjectDO> listSubjects(List<String> entryIds) {
        return entryIds == null || entryIds.isEmpty() ? List.of() : mapper.listSubjects(entryIds);
    }

    public int deleteBySourceRange(String sourceType, Instant from, Instant to) {
        return mapper.deleteBySourceRange(sourceType, from, to);
    }

    public TimelineProjectionCursorDO findCursor(String sourceType) {
        return mapper.findCursor(sourceType);
    }

    public void upsertCursor(TimelineProjectionCursorDO row) {
        mapper.upsertCursor(row);
    }
}

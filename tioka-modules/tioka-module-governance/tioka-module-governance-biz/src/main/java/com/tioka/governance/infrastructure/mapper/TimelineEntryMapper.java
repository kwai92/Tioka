package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.governance.infrastructure.persistence.TimelineEntryDO;
import com.tioka.governance.infrastructure.persistence.TimelineEntrySubjectDO;
import com.tioka.governance.infrastructure.persistence.TimelineProjectionCursorDO;
import com.tioka.governance.infrastructure.timeline.TimelinePageQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 统一时间线投影（OBS-08 / ADR-075 §2）：只增不改（重建时按来源区间清除后重放）。
 *
 * <p>投影由消费者异步写入（可能无用户 Principal），故旁路 ScopeLine；
 * 空间与数据标签隔离在服务层按 {@code workspaceId} / {@code data_label} 显式过滤（ADR-075 §4）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface TimelineEntryMapper {

    int insertEntry(TimelineEntryDO row);

    int insertSubject(TimelineEntrySubjectDO row);

    /** 幂等键查询：同一来源事件已投影的条目 id。 */
    String findIdBySourceEvent(@Param("sourceEventId") String sourceEventId);

    /** 请求时间线：按 {@code request_id} 游标分页。 */
    List<TimelineEntryDO> pageByRequest(@Param("requestId") String requestId,
                                        @Param("q") TimelinePageQuery query);

    /** 主体时间线：按 {@code (subject_type, subject_id)} 游标分页。 */
    List<TimelineEntryDO> pageBySubject(@Param("subjectType") String subjectType,
                                        @Param("subjectId") String subjectId,
                                        @Param("q") TimelinePageQuery query);

    /** 批量取条目关联主体（视图填充）。 */
    List<TimelineEntrySubjectDO> listSubjects(@Param("entryIds") List<String> entryIds);

    /** 重建：清除某来源在区间内的条目。 */
    int deleteBySourceRange(@Param("sourceType") String sourceType,
                            @Param("from") Instant from,
                            @Param("to") Instant to);

    TimelineProjectionCursorDO findCursor(@Param("sourceType") String sourceType);

    int upsertCursor(TimelineProjectionCursorDO row);
}

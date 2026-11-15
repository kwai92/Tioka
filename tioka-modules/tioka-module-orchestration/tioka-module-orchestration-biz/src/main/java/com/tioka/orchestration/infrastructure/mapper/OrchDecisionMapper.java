package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionTimelineDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrchDecisionMapper extends BaseMapper<OrchDecisionDO> {

    /**
     * 按时间段读回规则决定并左连 {@code orch_event} 补主体与空间（OBS-08 只读端口 / ADR-075 §2）。
     * 供 {@code OrchDecisionTimelineRebuildSource} 重建 {@code DECISION} 条目。
     */
    List<OrchDecisionTimelineDO> listByRange(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);
}

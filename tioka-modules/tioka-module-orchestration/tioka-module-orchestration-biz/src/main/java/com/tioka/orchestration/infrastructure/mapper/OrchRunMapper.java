package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface OrchRunMapper extends BaseMapper<OrchRunDO> {

    OrchRunDO lockById(@Param("id") String id);

    int countByWorkItem(@Param("workItemId") String workItemId);

    int countNonTerminalBefore(@Param("workItemId") String workItemId,
                               @Param("createAt") LocalDateTime createAt);

    int countDispatchedFacts(@Param("runId") String runId);
}

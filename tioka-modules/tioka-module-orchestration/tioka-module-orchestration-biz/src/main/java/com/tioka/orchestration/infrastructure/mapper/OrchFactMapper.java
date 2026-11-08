package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchFactMapper extends BaseMapper<OrchFactDO> {

    int maxSeq(@Param("runId") String runId);
}

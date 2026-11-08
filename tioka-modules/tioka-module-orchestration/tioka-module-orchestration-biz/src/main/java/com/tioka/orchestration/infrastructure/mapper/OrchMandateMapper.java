package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchMandateMapper extends BaseMapper<OrchMandateDO> {

    OrchMandateDO findLatestByWorkItem(@Param("workItemId") String workItemId);
}

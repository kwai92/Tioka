package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchEventMapper extends BaseMapper<OrchEventDO> {

    String findIdByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}

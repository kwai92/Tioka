package com.tioka.model.infrastructure.mapper;

import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface ModelRoutingPlanLockMapper {
        ModelRoutingPlanDO selectForShare(@Param("id") String id);
}

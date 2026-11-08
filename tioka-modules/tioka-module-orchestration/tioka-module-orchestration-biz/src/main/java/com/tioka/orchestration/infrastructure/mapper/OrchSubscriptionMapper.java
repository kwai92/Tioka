package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchSubscriptionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchSubscriptionMapper extends BaseMapper<OrchSubscriptionDO> {

    OrchSubscriptionDO findBySourceAndThread(@Param("source") String source,
                                             @Param("externalThreadId") String externalThreadId);
}

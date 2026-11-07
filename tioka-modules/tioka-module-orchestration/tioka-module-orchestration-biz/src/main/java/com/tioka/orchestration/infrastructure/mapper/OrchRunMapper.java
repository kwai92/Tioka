package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchRunMapper extends BaseMapper<OrchRunDO> {

    String lockNextClaimable(@Param("now") long now);

    int markLeased(@Param("id") String id,
                   @Param("workerId") String workerId,
                   @Param("leaseUntil") long leaseUntil);
}

package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyDemotionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrustAutonomyDemotionMapper extends BaseMapper<TrustAutonomyDemotionDO> {

    /** ON CONFLICT DO NOTHING：返回 1 表示本次是首次处理该降级事件族。 */
    int tryInsert(@Param("row") TrustAutonomyDemotionDO row);
}

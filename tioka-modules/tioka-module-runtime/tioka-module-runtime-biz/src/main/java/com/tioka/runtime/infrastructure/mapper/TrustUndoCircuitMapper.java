package com.tioka.runtime.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;

import java.time.LocalDateTime;

@Mapper
public interface TrustUndoCircuitMapper extends BaseMapper<TrustUndoCircuitDO> {

    /** V226：同一滑动窗口内只允许开启一次熔断；返回影响行数（1 = 本次触发）。 */
    int tryOpen(@Param("agentId") String agentId,
                @Param("windowStart") LocalDateTime windowStart,
                @Param("now") LocalDateTime now,
                @Param("reason") String reason);
}

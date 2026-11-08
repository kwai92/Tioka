package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrchTimerMapper extends BaseMapper<OrchTimerDO> {

    /** 多实例互斥领取到期行（FOR UPDATE SKIP LOCKED）。 */
    List<OrchTimerDO> claimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
}

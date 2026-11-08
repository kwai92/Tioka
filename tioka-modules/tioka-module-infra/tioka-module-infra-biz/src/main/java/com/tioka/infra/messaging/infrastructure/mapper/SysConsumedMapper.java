package com.tioka.infra.messaging.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysConsumedDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台级消费去重表，无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SysConsumedMapper extends BaseMapper<SysConsumedDO> {

    /** INSERT … ON CONFLICT DO NOTHING；返回影响行数（0=已消费）。 */
    int insertIgnore(@Param("consumer") String consumer, @Param("eventId") String eventId);
}

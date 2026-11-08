package com.tioka.connector.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.connector.infrastructure.persistence.ImInboundDedupeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 入站去重表无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ImInboundDedupeMapper extends BaseMapper<ImInboundDedupeDO> {

    /** INSERT … ON CONFLICT DO NOTHING；返回影响行数（0=重复）。 */
    int insertIgnore(@Param("bindingId") String bindingId, @Param("eventId") String eventId);
}

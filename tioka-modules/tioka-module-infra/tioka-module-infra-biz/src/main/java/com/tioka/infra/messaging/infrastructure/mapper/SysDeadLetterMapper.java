package com.tioka.infra.messaging.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.apache.ibatis.annotations.Mapper;

/** 平台级死信表，无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SysDeadLetterMapper extends BaseMapper<SysDeadLetterDO> {
}

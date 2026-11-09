package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.apache.ibatis.annotations.Mapper;

/** 外部交互台账（ADR-074）：只增不改；查询出口随 OBS-05/台账查询另行提供。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExtInteractionMapper {

    int insert(ExtInteractionWriteRow row);
}

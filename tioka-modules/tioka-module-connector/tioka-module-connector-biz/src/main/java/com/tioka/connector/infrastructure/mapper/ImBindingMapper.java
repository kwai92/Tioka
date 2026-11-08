package com.tioka.connector.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImBindingMapper extends BaseMapper<ImBindingDO> {

    /**
     * 入站回调无登录态；按主键旁路 ScopeLine 读取（随后仍校验 channel/status）。
     */
    @InterceptorIgnore(tenantLine = "true")
    ImBindingDO selectByIdUnscoped(@Param("id") String id);
}

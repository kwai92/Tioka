package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysGroupMapper extends BaseMapper<SysGroupDO> {

    @Select("SELECT id, name, source, external_id AS externalId FROM sys_group ORDER BY name")
    List<SysGroupDO> listAll();

    @Select("""
            SELECT g.id, g.name, g.source, g.external_id AS externalId
            FROM sys_group g
            JOIN sys_group_member m ON m.group_id = g.id
            WHERE m.user_id = #{userId}
            """)
    List<SysGroupDO> listByUser(@Param("userId") String userId);
}

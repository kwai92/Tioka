package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SysGroupMapper extends BaseMapper<SysGroupDO> {

        List<SysGroupDO> listAll();

        SysGroupDO findBySourceAndExternalId(@Param("source") String source,
                                         @Param("externalId") String externalId);

        List<SysGroupDO> listByUser(@Param("userId") String userId);

        List<String> groupIdsOfUserBySource(@Param("userId") String userId, @Param("source") String source);
}

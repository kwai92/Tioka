package com.tioka.connector.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.connector.infrastructure.persistence.ImOutboxDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface ImOutboxMapper extends BaseMapper<ImOutboxDO> {}

package com.tioka.runtime.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface RuntimeThreadMapper extends BaseMapper<RuntimeThreadDO> {}

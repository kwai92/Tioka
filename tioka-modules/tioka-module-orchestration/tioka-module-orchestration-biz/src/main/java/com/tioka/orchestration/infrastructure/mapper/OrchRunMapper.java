package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchRunMapper extends BaseMapper<OrchRunDO> {}

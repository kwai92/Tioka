package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelTurnFreezeMapper extends BaseMapper<ModelTurnFreezeDO> {}

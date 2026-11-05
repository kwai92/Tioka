package com.tioka.model.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelSessionFreezeDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface ModelSessionFreezeMapper extends BaseMapper<ModelSessionFreezeDO> {}

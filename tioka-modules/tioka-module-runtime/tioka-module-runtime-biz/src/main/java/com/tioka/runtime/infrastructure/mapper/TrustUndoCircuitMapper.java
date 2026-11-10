package com.tioka.runtime.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoCircuitDO;

@Mapper
public interface TrustUndoCircuitMapper extends BaseMapper<TrustUndoCircuitDO> {
}

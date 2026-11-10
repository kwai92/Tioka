package com.tioka.runtime.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.tioka.runtime.infrastructure.persistence.TrustUndoWindowDO;

@Mapper
public interface TrustUndoWindowMapper extends BaseMapper<TrustUndoWindowDO> {
}

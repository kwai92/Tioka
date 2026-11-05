package com.tioka.infra.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.infra.infrastructure.persistence.TraceSpanDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpanDO> {
}

package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalResultDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResultDO> {
}

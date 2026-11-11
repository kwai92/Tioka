package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalRunDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunDO> {
}

package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseDO> {
}

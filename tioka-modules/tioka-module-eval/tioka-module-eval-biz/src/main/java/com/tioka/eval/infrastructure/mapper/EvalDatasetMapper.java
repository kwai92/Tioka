package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalDatasetDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDatasetDO> {
}

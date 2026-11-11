package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评估器实例行读取旁路 ScopeLine（同 KbChunkMapper 先例）：内置行 workspace_id 为 NULL、
 * 为平台共享，行级过滤会把内置行滤空。空间隔离由调用方按 workspace_id 校验
 * （NULL = 平台内置放行；非 NULL 须等于当前空间）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface EvalEvaluatorMapper extends BaseMapper<EvalEvaluatorDO> {
}

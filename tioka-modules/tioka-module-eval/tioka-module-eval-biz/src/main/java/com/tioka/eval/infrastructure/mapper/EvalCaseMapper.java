package com.tioka.eval.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseDO> {

    /**
     * UnitSplit 校验查询（C-18 EVO-07 / ADR-068 L63）：同一空间内各材料单元键
     * 已落到的分层（跨数据集含历史版本，去重）。eval_case 无 workspace_id，
     * 经 eval_dataset 归属（V176 workspace-scope-exempt 口径）。
     * SQL 放 mapper/EvalCaseMapper.xml（48-CS §4 架构纪律）。
     */
    List<Map<String, Object>> selectUnitTiers(@Param("workspaceId") String workspaceId,
                                              @Param("unitKeys") Collection<String> unitKeys);
}

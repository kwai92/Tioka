package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrchWorkItemMapper extends BaseMapper<OrchWorkItemDO> {

    OrchWorkItemDO findByRuleAndSubject(@Param("ruleId") String ruleId,
                                        @Param("subjectKey") String subjectKey);

    OrchWorkItemDO lockByRuleAndSubject(@Param("ruleId") String ruleId,
                                        @Param("subjectKey") String subjectKey);

    int updateLocked(OrchWorkItemDO row);
}

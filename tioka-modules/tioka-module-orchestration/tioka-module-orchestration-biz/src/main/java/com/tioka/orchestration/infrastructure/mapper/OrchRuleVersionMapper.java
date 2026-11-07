package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchRuleVersionMapper extends BaseMapper<OrchRuleVersionDO> {}

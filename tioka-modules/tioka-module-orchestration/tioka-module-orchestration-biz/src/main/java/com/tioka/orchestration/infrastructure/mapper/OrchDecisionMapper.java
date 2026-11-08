package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
<<<<<<<< HEAD:tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz/src/main/java/com/tioka/orchestration/infrastructure/mapper/OrchEventMapper.java
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchEventMapper extends BaseMapper<OrchEventDO> {}
========
import com.tioka.orchestration.infrastructure.persistence.OrchDecisionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchDecisionMapper extends BaseMapper<OrchDecisionDO> {
}
>>>>>>>> main:tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz/src/main/java/com/tioka/orchestration/infrastructure/mapper/OrchDecisionMapper.java

package com.tioka.knowledge.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.knowledge.infrastructure.persistence.KbDatasourceDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface KbDatasourceMapper extends BaseMapper<KbDatasourceDO> {}

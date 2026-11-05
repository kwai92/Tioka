package com.tioka.flow.infrastructure.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.flow.infrastructure.persistence.FlowVersionDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface FlowVersionMapper extends BaseMapper<FlowVersionDO> {}

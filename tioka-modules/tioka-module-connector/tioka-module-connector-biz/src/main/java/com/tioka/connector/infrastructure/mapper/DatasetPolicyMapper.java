package com.tioka.connector.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.connector.infrastructure.persistence.DatasetPolicyDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasetPolicyMapper extends BaseMapper<DatasetPolicyDO> {}

package com.tioka.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.knowledge.infrastructure.persistence.WikiPageDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPageDO> {}

package com.tioka.knowledge.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface KbBaseLockMapper {
        String selectIdForShare(@Param("id") String id);
}

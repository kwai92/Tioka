package com.tioka.knowledge.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbBaseLockMapper {
    @Select("""
            SELECT id FROM kb_base
            WHERE id = #{id} AND deleted = FALSE
            FOR SHARE
            """)
    String selectIdForShare(@Param("id") String id);
}

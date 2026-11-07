package com.tioka.skill.infrastructure.mapper;

import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillPackageLockMapper {
    @Select("""
            SELECT id, name, published, skill_md AS skillMd,
                   workspace_id AS workspaceId, owner_id AS ownerId,
                   create_at AS createAt, update_at AS updateAt, create_by AS createBy,
                   update_by AS updateBy, deleted
            FROM skill_package
            WHERE id = #{id} AND deleted = FALSE
            FOR SHARE
            """)
    SkillPackageDO selectForShare(@Param("id") String id);
}

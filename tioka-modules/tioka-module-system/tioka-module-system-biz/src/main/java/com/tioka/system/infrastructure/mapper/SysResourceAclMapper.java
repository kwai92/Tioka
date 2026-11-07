package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysResourceAclDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysResourceAclMapper extends BaseMapper<SysResourceAclDO> {

    @Select("""
            SELECT resource_type AS resourceType, resource_id AS resourceId,
                   subject_type AS subjectType, subject_id AS subjectId, level
            FROM sys_resource_acl
            WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
            """)
    List<SysResourceAclDO> listByResource(@Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId);

    @Delete("""
            DELETE FROM sys_resource_acl
            WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
            """)
    int deleteByResource(@Param("resourceType") String resourceType,
                         @Param("resourceId") String resourceId);

    @Insert("""
            INSERT INTO sys_resource_acl
              (resource_type, resource_id, subject_type, subject_id, level,
               create_at, update_at, create_by, update_by)
            VALUES
              (#{resourceType}, #{resourceId}, #{subjectType}, #{subjectId}, #{level},
               now(), now(), 'system', 'system')
            ON CONFLICT (resource_type, resource_id, subject_type, subject_id)
            DO UPDATE SET level = EXCLUDED.level, update_at = now()
            """)
    int upsert(@Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId,
               @Param("subjectType") String subjectType,
               @Param("subjectId") String subjectId,
               @Param("level") String level);

    @Select("""
            SELECT resource_type AS resourceType, resource_id AS resourceId,
                   subject_type AS subjectType, subject_id AS subjectId, level
            FROM sys_resource_acl
            WHERE resource_type = #{resourceType}
              AND (
                (subject_type = 'user' AND subject_id = #{userId})
                OR (subject_type = 'workspace' AND subject_id = #{workspaceId})
                OR (subject_type = 'group' AND subject_id = ANY(#{groupIds}::varchar[]))
              )
            """)
    List<SysResourceAclDO> listMatching(@Param("resourceType") String resourceType,
                                        @Param("userId") String userId,
                                        @Param("workspaceId") String workspaceId,
                                        @Param("groupIds") String[] groupIds);
}

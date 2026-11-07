package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysGroupMemberMapper {

    @Select("SELECT group_id FROM sys_group_member WHERE user_id = #{userId}")
    List<String> groupIdsOfUser(@Param("userId") String userId);

    @Select("SELECT user_id FROM sys_group_member WHERE group_id = #{groupId}")
    List<String> userIdsOfGroup(@Param("groupId") String groupId);

    @Insert("""
            INSERT INTO sys_group_member (group_id, user_id, create_at)
            VALUES (#{groupId}, #{userId}, now())
            ON CONFLICT DO NOTHING
            """)
    int insert(@Param("groupId") String groupId, @Param("userId") String userId);

    @Delete("DELETE FROM sys_group_member WHERE group_id = #{groupId}")
    int deleteAllOfGroup(@Param("groupId") String groupId);

    @Delete("DELETE FROM sys_group_member WHERE group_id = #{groupId} AND user_id = #{userId}")
    int delete(@Param("groupId") String groupId, @Param("userId") String userId);

    @Delete("""
            DELETE FROM sys_group_member m
            USING sys_group g
            WHERE m.group_id = g.id
              AND m.user_id = #{userId}
              AND g.source = #{source}
            """)
    int deleteUserFromSource(@Param("userId") String userId, @Param("source") String source);
}

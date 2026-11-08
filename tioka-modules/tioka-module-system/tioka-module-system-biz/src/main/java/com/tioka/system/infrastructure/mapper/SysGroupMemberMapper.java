package com.tioka.system.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SysGroupMemberMapper {

        List<String> groupIdsOfUser(@Param("userId") String userId);

        List<String> userIdsOfGroup(@Param("groupId") String groupId);

        int insert(@Param("groupId") String groupId, @Param("userId") String userId);

        int deleteAllOfGroup(@Param("groupId") String groupId);

        int delete(@Param("groupId") String groupId, @Param("userId") String userId);

        int deleteUserFromSource(@Param("userId") String userId, @Param("source") String source);
}

package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysResourceAclDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SysResourceAclMapper extends BaseMapper<SysResourceAclDO> {

        List<SysResourceAclDO> listByResource(@Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId);

        int deleteByResource(@Param("resourceType") String resourceType,
                         @Param("resourceId") String resourceId);

        int upsert(@Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId,
               @Param("subjectType") String subjectType,
               @Param("subjectId") String subjectId,
               @Param("level") String level);

        List<SysResourceAclDO> listMatching(@Param("resourceType") String resourceType,
                                        @Param("userId") String userId,
                                        @Param("workspaceId") String workspaceId,
                                        @Param("groupIds") String[] groupIds);
}

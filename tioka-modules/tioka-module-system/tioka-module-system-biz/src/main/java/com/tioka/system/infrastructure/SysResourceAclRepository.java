package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.SysResourceAclMapper;
import com.tioka.system.infrastructure.persistence.SysResourceAclDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SysResourceAclRepository {
    private final SysResourceAclMapper mapper;

    public SysResourceAclRepository(SysResourceAclMapper mapper) {
        this.mapper = mapper;
    }

    public List<SysResourceAclDO> listByResource(String resourceType, String resourceId) {
        return mapper.listByResource(resourceType, resourceId);
    }

    public void replace(String resourceType, String resourceId, List<SysResourceAclDO> entries) {
        mapper.deleteByResource(resourceType, resourceId);
        if (entries == null) {
            return;
        }
        for (SysResourceAclDO e : entries) {
            mapper.upsert(resourceType, resourceId, e.getSubjectType(), e.getSubjectId(), e.getLevel());
        }
    }

    public void upsert(String resourceType, String resourceId,
                       String subjectType, String subjectId, String level) {
        mapper.upsert(resourceType, resourceId, subjectType, subjectId, level);
    }

    public List<SysResourceAclDO> listMatching(String resourceType, String userId,
                                               String workspaceId, List<String> groupIds) {
        String[] gids = groupIds == null || groupIds.isEmpty()
                ? new String[0]
                : groupIds.toArray(String[]::new);
        return mapper.listMatching(resourceType, userId, workspaceId, gids);
    }
}

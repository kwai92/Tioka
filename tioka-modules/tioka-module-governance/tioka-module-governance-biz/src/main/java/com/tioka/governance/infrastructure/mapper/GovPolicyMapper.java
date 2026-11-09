package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** gov_policy Mapper（自定义 SQL 在 mapper/GovPolicyMapper.xml）。 */
public interface GovPolicyMapper extends BaseMapper<GovPolicyDO> {

    /** 参与求值：系统级（workspace_id NULL）+ 当前空间，ACTIVE。 */
    List<GovPolicyDO> listActiveForWorkspace(@Param("workspaceId") String workspaceId);

    List<GovPolicyDO> listBySource(@Param("workspaceId") String workspaceId,
                                   @Param("source") String source);

    GovPolicyDO findById(@Param("id") String id);

    int maxVersion(@Param("workspaceId") String workspaceId, @Param("source") String source);

    int activeVersion(@Param("workspaceId") String workspaceId, @Param("source") String source);

    /** charter 来源替换：整组停用（编译幂等覆盖用；旧行保留满足审计回溯）。 */
    int disableBySource(@Param("workspaceId") String workspaceId, @Param("source") String source);
}

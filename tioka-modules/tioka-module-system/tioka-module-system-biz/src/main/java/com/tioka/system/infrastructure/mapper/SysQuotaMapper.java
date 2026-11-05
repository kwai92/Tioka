package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.SysQuotaDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysQuotaMapper extends BaseMapper<SysQuotaDO> {
    @Update("""
            UPDATE sys_quota SET concurrent_used = concurrent_used + 1
            WHERE workspace_id=#{workspaceId} AND concurrent_used < #{limit}
            """)
    int tryAcquire(@Param("workspaceId") String workspaceId, @Param("limit") int limit);

    @Update("""
            UPDATE sys_quota SET concurrent_used = CASE WHEN concurrent_used > 0 THEN concurrent_used - 1 ELSE 0 END
            WHERE workspace_id=#{workspaceId}
            """)
    int release(@Param("workspaceId") String workspaceId);

    @Update("""
            UPDATE sys_quota SET token_used = token_used + #{tokens}
            WHERE workspace_id=#{workspaceId} AND token_used + #{tokens} <= #{tokenLimit}
            """)
    int consumeTokens(@Param("workspaceId") String workspaceId,
                      @Param("tokens") int tokens,
                      @Param("tokenLimit") int tokenLimit);
}

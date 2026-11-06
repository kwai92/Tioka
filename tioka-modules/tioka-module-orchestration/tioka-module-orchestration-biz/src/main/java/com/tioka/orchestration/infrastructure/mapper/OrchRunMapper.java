package com.tioka.orchestration.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrchRunMapper extends BaseMapper<OrchRunDO> {

    @Select("""
            SELECT id FROM orch_run
            WHERE deleted = FALSE
              AND (status = 'READY' OR (status = 'LEASED' AND lease_until < #{now}))
            ORDER BY create_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """)
    String lockNextClaimable(@Param("now") long now);

    @Update("""
            UPDATE orch_run
            SET status = 'LEASED', worker_id = #{workerId}, lease_until = #{leaseUntil},
                update_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND deleted = FALSE
            """)
    int markLeased(@Param("id") String id,
                   @Param("workerId") String workerId,
                   @Param("leaseUntil") long leaseUntil);
}

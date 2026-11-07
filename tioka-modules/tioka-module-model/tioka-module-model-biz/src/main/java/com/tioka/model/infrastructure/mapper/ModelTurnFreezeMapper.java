package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ModelTurnFreezeMapper extends BaseMapper<ModelTurnFreezeDO> {

    /**
     * 轮次冻结写入：冲突时以先写者为准（MH-01）。
     *
     * @return 插入行数；0 表示已有冻结
     */
    @Insert("""
            INSERT INTO model_turn_freeze
                (session_id, turn_id, binding_id, reason, failover_count, create_by, update_by)
            VALUES
                (#{sessionId}, #{turnId}, #{bindingId}, #{reason}, 0, 'system', 'system')
            ON CONFLICT (session_id, turn_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("sessionId") String sessionId,
                       @Param("turnId") String turnId,
                       @Param("bindingId") String bindingId,
                       @Param("reason") String reason);

    @Select("""
            SELECT session_id AS sessionId, turn_id AS turnId, binding_id AS bindingId,
                   reason, failover_count AS failoverCount
            FROM model_turn_freeze
            WHERE session_id = #{sessionId}
            ORDER BY create_at
            """)
    List<ModelTurnFreezeDO> listBySession(@Param("sessionId") String sessionId);

    /** MH-02：切换冻结 binding，failover_count + 1。 */
    @Update("""
            UPDATE model_turn_freeze
            SET binding_id = #{bindingId},
                reason = #{reason},
                failover_count = COALESCE(failover_count, 0) + 1,
                update_by = 'system',
                update_at = now()
            WHERE session_id = #{sessionId} AND turn_id = #{turnId}
            """)
    int recordFailover(@Param("sessionId") String sessionId,
                       @Param("turnId") String turnId,
                       @Param("bindingId") String bindingId,
                       @Param("reason") String reason);
}

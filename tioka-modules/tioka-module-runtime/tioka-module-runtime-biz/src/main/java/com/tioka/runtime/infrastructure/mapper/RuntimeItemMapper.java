package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RuntimeItemMapper {

    @Select("""
            SELECT 1 FROM runtime_turn
             WHERE id = #{turnId} AND lease_version = #{leaseVersion} AND status = 'RUNNING'
             FOR SHARE
            """)
    Integer assertLease(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

    @Select("""
            UPDATE runtime_session
               SET last_item_seq = last_item_seq + 1, update_at = now()
             WHERE id = #{sessionId}
         RETURNING last_item_seq
            """)
    Long nextItemSeq(String sessionId);

    @Insert("""
            INSERT INTO runtime_item (
              session_id, seq, id, turn_id, step, type, role, content_json, token_count, discarded, create_at, create_by
            ) VALUES (
              #{sessionId}, #{seq}, #{id}, #{turnId}, #{step}, #{type}, #{role}, #{contentJson},
              #{tokenCount}, COALESCE(#{discarded}, FALSE), now(), 'system'
            )
            """)
    void insert(RuntimeItemDO item);

    @Select("""
            SELECT session_id AS sessionId, seq, id, turn_id AS turnId, step, type, role,
                   content_json AS contentJson, token_count AS tokenCount, discarded
            FROM runtime_item
            WHERE session_id = #{sessionId}
            ORDER BY seq
            """)
    java.util.List<RuntimeItemDO> listBySession(String sessionId);

    @Select("""
            SELECT session_id AS sessionId, seq, id, turn_id AS turnId, step, type, role,
                   content_json AS contentJson, token_count AS tokenCount, discarded
            FROM runtime_item
            WHERE session_id = #{sessionId} AND seq > #{afterSeq}
            ORDER BY seq
            LIMIT #{limit}
            """)
    java.util.List<RuntimeItemDO> listAfterSeq(@Param("sessionId") String sessionId,
                                               @Param("afterSeq") long afterSeq,
                                               @Param("limit") int limit);

    @Select("""
            SELECT session_id AS sessionId, seq, id, turn_id AS turnId, step, type, role,
                   content_json AS contentJson, token_count AS tokenCount, discarded
            FROM runtime_item
            WHERE turn_id = #{turnId}
            ORDER BY seq
            """)
    java.util.List<RuntimeItemDO> listByTurn(String turnId);
}

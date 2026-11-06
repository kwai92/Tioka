package com.tioka.system.infrastructure.mapper;

import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface GovQuotaMapper {

    @Select("""
            SELECT scope_type AS scopeType, scope_id AS scopeId, metric,
                   limit_value AS limitValue, used_value AS usedValue,
                   window_start AS windowStart,
                   create_at AS createAt, update_at AS updateAt,
                   create_by AS createBy, update_by AS updateBy
            FROM gov_quota
            WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} AND metric = #{metric}
            """)
    GovQuotaDO find(@Param("scopeType") String scopeType,
                    @Param("scopeId") String scopeId,
                    @Param("metric") String metric);

    @Select("""
            SELECT scope_type AS scopeType, scope_id AS scopeId, metric,
                   limit_value AS limitValue, used_value AS usedValue,
                   window_start AS windowStart,
                   create_at AS createAt, update_at AS updateAt,
                   create_by AS createBy, update_by AS updateBy
            FROM gov_quota
            WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
            ORDER BY metric
            """)
    List<GovQuotaDO> listByScope(@Param("scopeType") String scopeType,
                                 @Param("scopeId") String scopeId);

    @Select("""
            SELECT scope_type AS scopeType, scope_id AS scopeId, metric,
                   limit_value AS limitValue, used_value AS usedValue,
                   window_start AS windowStart,
                   create_at AS createAt, update_at AS updateAt,
                   create_by AS createBy, update_by AS updateBy
            FROM gov_quota
            WHERE metric = #{metric}
            """)
    List<GovQuotaDO> listByMetric(@Param("metric") String metric);

    @Insert("""
            INSERT INTO gov_quota (scope_type, scope_id, metric, limit_value, used_value, window_start,
                                   create_at, update_at, create_by, update_by)
            VALUES (#{scopeType}, #{scopeId}, #{metric}, #{limitValue}, COALESCE(#{usedValue}, 0), #{windowStart},
                    now(), now(), COALESCE(#{createBy}, 'system'), COALESCE(#{updateBy}, 'system'))
            ON CONFLICT (scope_type, scope_id, metric) DO UPDATE SET
                limit_value = EXCLUDED.limit_value,
                update_at = now(),
                update_by = EXCLUDED.update_by
            """)
    int upsertLimit(GovQuotaDO row);

    @Update("""
            UPDATE gov_quota
               SET used_value = #{usedValue},
                   window_start = #{windowStart},
                   update_at = now(),
                   update_by = COALESCE(#{updateBy}, 'system')
             WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} AND metric = #{metric}
            """)
    int updateUsed(@Param("scopeType") String scopeType,
                   @Param("scopeId") String scopeId,
                   @Param("metric") String metric,
                   @Param("usedValue") long usedValue,
                   @Param("windowStart") OffsetDateTime windowStart,
                   @Param("updateBy") String updateBy);

    @Delete("""
            DELETE FROM gov_quota
             WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} AND metric = #{metric}
            """)
    int delete(@Param("scopeType") String scopeType,
               @Param("scopeId") String scopeId,
               @Param("metric") String metric);
}

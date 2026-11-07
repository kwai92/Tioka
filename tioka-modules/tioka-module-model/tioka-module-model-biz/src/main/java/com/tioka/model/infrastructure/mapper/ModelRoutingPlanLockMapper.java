package com.tioka.model.infrastructure.mapper;

import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelRoutingPlanLockMapper {
    @Select("""
            SELECT id, name, type, primary_binding_id AS primaryBindingId,
                   fallback_binding_id AS fallbackBindingId, hard_binding_id AS hardBindingId,
                   published, snapshot_hash AS snapshotHash, create_at AS createAt, create_by AS createBy
            FROM model_routing_plan
            WHERE id = #{id}
            FOR SHARE
            """)
    ModelRoutingPlanDO selectForShare(@Param("id") String id);
}

package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.system.infrastructure.persistence.EvidencePackDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 证据包登记读写（OBS-12 / ADR-075 §5）。
 *
 * <p>导出可能为平台级（{@code workspace_id} 为空，不限空间），故旁路 ScopeLine，
 * 空间隔离在服务层按 {@code workspace_id} 显式校验。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface EvidencePackMapper extends BaseMapper<EvidencePackDO> {

    /** 按范围取登记（可选限定空间），按导出时间倒序；调用方保证 {@code limit} 已收敛。 */
    List<EvidencePackDO> listByScope(@Param("scopeType") String scopeType,
                                     @Param("scopeRef") String scopeRef,
                                     @Param("workspaceId") String workspaceId,
                                     @Param("limit") int limit);
}

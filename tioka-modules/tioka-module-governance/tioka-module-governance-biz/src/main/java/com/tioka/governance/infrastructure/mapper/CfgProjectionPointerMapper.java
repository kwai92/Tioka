package com.tioka.governance.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 更新 V150 投影表 current_version_* 指针（已知类型）。 */
@Mapper
public interface CfgProjectionPointerMapper {

    int updateProvider(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);

    int updateBinding(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);

    int updateLogical(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);

    int updateRoutingPlan(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);
}

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

    /** MH-08（V151）：投影表已重命名为 model_alias。 */
    int updateAlias(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);

    int updateRoutingPlan(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);

    /** AST-07（ADR-041 §4）：Prompt 模板身份表 prm_template 的当前生效版本指针。 */
    int updatePromptTemplate(
            @Param("objectId") String objectId,
            @Param("versionId") String versionId,
            @Param("versionNo") int versionNo);
}

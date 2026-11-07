package com.tioka.governance.infrastructure.mapper;

import com.tioka.governance.infrastructure.persistence.CfgDraftDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CfgDraftMapper {

    CfgDraftDO findOne(@Param("objectType") String objectType, @Param("objectId") String objectId);

    int insert(CfgDraftDO row);

    int updateOptimistic(@Param("row") CfgDraftDO row, @Param("expectedRevision") long expectedRevision);
}

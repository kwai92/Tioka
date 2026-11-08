package com.tioka.governance.infrastructure.mapper;

import com.tioka.governance.infrastructure.persistence.CfgVersionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CfgVersionMapper {

    int insert(CfgVersionDO row);

    CfgVersionDO findByNo(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("versionNo") int versionNo);

    CfgVersionDO findById(@Param("versionId") String versionId);

    Integer maxVersionNo(@Param("objectType") String objectType, @Param("objectId") String objectId);
}

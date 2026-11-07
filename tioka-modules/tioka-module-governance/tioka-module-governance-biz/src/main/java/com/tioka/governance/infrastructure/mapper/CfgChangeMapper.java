package com.tioka.governance.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CfgChangeMapper {

    int insert(
            @Param("objectType") String objectType,
            @Param("targetKey") String targetKey,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("traceId") String traceId);
}

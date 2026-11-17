package com.tioka.governance.infrastructure.mapper;

import com.tioka.governance.infrastructure.persistence.CfgChangeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /** OBS-08 时间线重建：按时间段读回配置变更（ADR-075 §2 主体时间线的灰色背景条目）。 */
    List<CfgChangeDO> listByRange(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);
}

package com.tioka.system.infrastructure.mapper;

import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface GovQuotaMapper {

        GovQuotaDO find(@Param("scopeType") String scopeType,
                    @Param("scopeId") String scopeId,
                    @Param("metric") String metric);

        List<GovQuotaDO> listByScope(@Param("scopeType") String scopeType,
                                 @Param("scopeId") String scopeId);

        List<GovQuotaDO> listByMetric(@Param("metric") String metric);

        int upsertLimit(GovQuotaDO row);

        int updateUsed(@Param("scopeType") String scopeType,
                   @Param("scopeId") String scopeId,
                   @Param("metric") String metric,
                   @Param("usedValue") long usedValue,
                   @Param("windowStart") OffsetDateTime windowStart,
                   @Param("updateBy") String updateBy);

        int delete(@Param("scopeType") String scopeType,
               @Param("scopeId") String scopeId,
               @Param("metric") String metric);
}

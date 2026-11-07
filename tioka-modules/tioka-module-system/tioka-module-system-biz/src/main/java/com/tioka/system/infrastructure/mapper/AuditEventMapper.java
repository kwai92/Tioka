package com.tioka.system.infrastructure.mapper;

import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import com.tioka.system.infrastructure.persistence.AuditEventWriteRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditEventMapper {

    int insert(AuditEventWriteRow row);

    List<Map<String, Object>> query(AuditEventQuery q);

    List<Map<String, Object>> listUnforwarded(@Param("limit") int limit);

    int markForwardedOne(@Param("id") String id);

    int deleteExpiredForwarded(@Param("before") Instant before);
}

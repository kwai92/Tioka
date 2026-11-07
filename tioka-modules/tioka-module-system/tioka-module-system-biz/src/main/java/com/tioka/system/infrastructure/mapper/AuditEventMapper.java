package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import com.tioka.system.infrastructure.persistence.AuditEventWriteRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 审计表 SQL 自行按 workspaceId 过滤；插入也带显式 workspace_id。
 * 忽略 ScopeLine，避免查询端无 Principal（转发/测试直查）时被 deny-by-default 拦住。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AuditEventMapper {

    int insert(AuditEventWriteRow row);

    List<Map<String, Object>> query(AuditEventQuery q);

    List<Map<String, Object>> listUnforwarded(@Param("limit") int limit);

    int markForwardedOne(@Param("id") String id);

    int deleteExpiredForwarded(@Param("before") Instant before);
}

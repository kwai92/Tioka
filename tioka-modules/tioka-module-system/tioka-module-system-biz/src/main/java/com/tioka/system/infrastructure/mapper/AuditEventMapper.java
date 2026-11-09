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

    /* ---- GOV-09 审计哈希链封印（V219 / ADR-025） ---- */

    /** 取未封印记录（hash IS NULL），按 id ASC——Monotonic ULID 保证即时间序。 */
    List<Map<String, Object>> listUnsealed(@Param("limit") int limit);

    /** 封印回写：prev_hash + hash 一次落库。 */
    int markSealed(@Param("id") String id,
                   @Param("prevHash") String prevHash,
                   @Param("hash") String hash);

    /** 封印锚 upsert：每租户一条链，链末哈希落锚（可外送 SIEM）。 */
    int upsertSeal(@Param("workspaceKey") String workspaceKey,
                   @Param("lastId") String lastId,
                   @Param("lastHash") String lastHash,
                   @Param("count") int count);

    /** 校验用：取指定链全部已封印记录（含 prev_hash/hash），按 id ASC。 */
    List<Map<String, Object>> listSealedByChain(@Param("workspaceKey") String workspaceKey);

    /** 校验用：读取封印锚。 */
    Map<String, Object> getSeal(@Param("workspaceKey") String workspaceKey);

    /* ---- OBS-05 转发互斥与水位（V221 / ADR-072） ---- */

    /** PG advisory lock（会话级；须在同一事务/连接内取用与释放）。 */
    boolean tryAdvisoryLock(@Param("lockKey") String lockKey);

    /** 释放 advisory lock（与 tryAdvisoryLock 同连接）。 */
    boolean advisoryUnlock(@Param("lockKey") String lockKey);

    /** 转发水位 upsert：记录本通道已外送的最后一条记录 ID（双实例互斥后单调推进）。 */
    int updateForwardProgress(@Param("channel") String channel,
                              @Param("lastForwardedId") String lastForwardedId,
                              @Param("batchSize") int batchSize);
}
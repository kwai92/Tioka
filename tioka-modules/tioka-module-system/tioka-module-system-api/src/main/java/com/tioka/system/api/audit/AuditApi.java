package com.tioka.system.api.audit;

import java.time.Instant;
import java.util.List;

/** 审计写入与查询（ADR-017）。外送 SIEM 由 {@code AuditSink} 异步完成。 */
public interface AuditApi {

    /** 在当前事务内写入（SUCCESS）。 */
    void record(AuditEvent event);

    /** 在独立事务内写入（DENIED / ERROR）。 */
    void recordIndependent(AuditEvent event);

    List<AuditEvent> query(AuditQuery query);

    /**
     * 审计查询条件（ADR-072 §6 OBS-09）。
     *
     * <p>关联键条件 {@code traceId}/{@code requestId}/{@code subject}/{@code turnId}/{@code runId}
     * 遵循 ADR-073 §1；分页改为按 {@code (ts, id)} 的游标（{@code cursor} 为不透明串），不再用偏移量。
     * {@code workspaceId} 为 null 表示不加空间条件（仅全局权限持有者可达）。
     */
    record AuditQuery(String workspaceId, String actorId, String action, String resourceType,
                      String resourceId, AuditOutcome outcome, Instant from, Instant to,
                      String traceId, String requestId, String subject, String turnId, String runId,
                      String cursor, int limit) {

        /** 兼容旧 10 参口径（关联键条件为 null；cursor 语义同 {@code (ts, id)} 游标）。 */
        public AuditQuery(String workspaceId, String actorId, String action, String resourceType,
                          String resourceId, AuditOutcome outcome, Instant from, Instant to,
                          String cursor, int limit) {
            this(workspaceId, actorId, action, resourceType, resourceId, outcome, from, to,
                    null, null, null, null, null, cursor, limit);
        }
    }

    /**
     * 审计分页游标（ADR-072 §6）：按 {@code (ts, id)} 定位，不透明串形如 {@code <instant>|<id>}。
     *
     * <p>查询结果按 {@code ts DESC, id DESC} 排序；把上一页末行的 {@code ts}/{@code id} 编码为游标即可续查。
     */
    record AuditCursor(Instant ts, String id) {

        public String encode() {
            return ts == null || id == null ? null : ts + "|" + id;
        }

        public static AuditCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int i = cursor.indexOf('|');
            if (i <= 0 || i == cursor.length() - 1) {
                return null;
            }
            try {
                return new AuditCursor(Instant.parse(cursor.substring(0, i)), cursor.substring(i + 1));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}

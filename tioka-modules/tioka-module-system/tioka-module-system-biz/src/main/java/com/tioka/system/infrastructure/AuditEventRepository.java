package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.AuditEventMapper;
import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import com.tioka.system.infrastructure.persistence.AuditEventWriteRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditEventRepository {
    private final AuditEventMapper mapper;

    public AuditEventRepository(AuditEventMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AuditEventWriteRow row) {
        mapper.insert(row);
    }

    public List<Map<String, Object>> query(AuditEventQuery q) {
        return mapper.query(q);
    }

    /** 兼容旧调用（FND-04 IT）。 */
    public List<Map<String, Object>> query(String workspaceId, String actorId, String action,
                                           String outcome, int limit) {
        return query(new AuditEventQuery(workspaceId, actorId, action, null, null, outcome,
                null, null, limit, 0));
    }

    public List<Map<String, Object>> listUnforwarded(int limit) {
        return mapper.listUnforwarded(limit);
    }

    public void markForwarded(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                mapper.markForwardedOne(id);
            }
        }
    }

    public int deleteExpiredForwarded(Instant before) {
        return mapper.deleteExpiredForwarded(before);
    }

    /* ---- GOV-09 审计哈希链封印（V219 / ADR-025） ---- */

    /** 取未封印记录（hash IS NULL），按 id ASC——Monotonic ULID 保证即时间序。 */
    public List<Map<String, Object>> listUnsealed(int limit) {
        return mapper.listUnsealed(limit);
    }

    /** 封印回写：prev_hash + hash 一次落库。 */
    public void markSealed(String id, String prevHash, String hash) {
        mapper.markSealed(id, prevHash, hash);
    }

    /** 封印锚 upsert：每租户一条链，链末哈希落锚（可外送 SIEM）。 */
    public void upsertSeal(String workspaceKey, String lastId, String lastHash, int count) {
        mapper.upsertSeal(workspaceKey, lastId, lastHash, count);
    }

    /** 校验用：取指定链全部已封印记录（含 prev_hash/hash），按 id ASC。 */
    public List<Map<String, Object>> listSealedByChain(String workspaceKey) {
        return mapper.listSealedByChain(workspaceKey);
    }

    /** 校验用：读取封印锚。 */
    public Map<String, Object> getSeal(String workspaceKey) {
        return mapper.getSeal(workspaceKey);
    }
}

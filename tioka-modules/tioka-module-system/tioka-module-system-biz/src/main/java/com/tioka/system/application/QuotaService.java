package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.application.quota.DefaultQuotaGuard;
import com.tioka.system.application.quota.QuotaMetrics;
import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * GOV-03：配额查询与上限管理（基于 {@code gov_quota} + Redis）。
 */
@Service
public class QuotaService {
    private final GovQuotaRepository quotas;
    private final DefaultQuotaGuard guard;
    private final PrincipalHolder principals;

    public QuotaService(GovQuotaRepository quotas, DefaultQuotaGuard guard, PrincipalHolder principals) {
        this.quotas = quotas;
        this.guard = guard;
        this.principals = principals;
    }

    public record QuotaView(String metric, Long limit, long used, OffsetDateTime windowStart) {}

    public List<QuotaView> list(String scopeType, String scopeId) {
        requireScope(scopeType, scopeId);
        List<GovQuotaDO> rows = quotas.listByScope(scopeType, scopeId);
        List<QuotaView> out = new ArrayList<>(rows.size());
        OffsetDateTime monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        for (GovQuotaDO row : rows) {
            long used = usedOf(scopeType, scopeId, row.getMetric(), row);
            out.add(new QuotaView(row.getMetric(), row.getLimitValue(), used,
                    row.getWindowStart() == null ? monthStart : row.getWindowStart()));
        }
        return out;
    }

    /**
     * @param limit null 表示删除上限（不限）
     */
    public void putLimit(String scopeType, String scopeId, String metric, Long limit) {
        requireScope(scopeType, scopeId);
        if (!QuotaMetrics.ALL_METRICS.contains(metric)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知 metric: " + metric);
        }
        String actor = actorId();
        if (limit == null) {
            quotas.delete(scopeType, scopeId, metric);
            guard.invalidateLimitCache(scopeType, scopeId, metric);
            return;
        }
        if (limit < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "limit 不能为负");
        }
        quotas.upsertLimit(scopeType, scopeId, metric, limit, actor);
        guard.invalidateLimitCache(scopeType, scopeId, metric);
    }

    private long usedOf(String scopeType, String scopeId, String metric, GovQuotaDO row) {
        if (QuotaMetrics.TOKENS_MONTH.equals(metric)) {
            return guard.usedTokens(scopeType, scopeId);
        }
        if (QuotaMetrics.CONCURRENT_TURNS.equals(metric)) {
            // 实时并发见 Redis ZCARD；此处 REST used 用 DB 快照（Flusher 不写并发）
            return row.getUsedValue() == null ? 0L : row.getUsedValue();
        }
        return row.getUsedValue() == null ? 0L : row.getUsedValue();
    }

    private static void requireScope(String scopeType, String scopeId) {
        if (scopeType == null || scopeType.isBlank() || !QuotaMetrics.ALL_SCOPES.contains(scopeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scopeType 无效");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scopeId 必填");
        }
    }

    private String actorId() {
        return principals.find().map(Principal::userId).orElse("system");
    }
}

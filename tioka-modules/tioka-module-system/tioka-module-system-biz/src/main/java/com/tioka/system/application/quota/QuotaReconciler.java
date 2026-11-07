package com.tioka.system.application.quota;

import com.tioka.system.api.spi.TokenUsageSumPort;
import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 每小时用 {@code SUM(model_usage)} 校正 Redis 计数（取较大值，避免少算）。
 */
@Component
public class QuotaReconciler {
    private static final Logger log = LoggerFactory.getLogger(QuotaReconciler.class);

    private final StringRedisTemplate redis;
    private final GovQuotaRepository quotas;
    private final DefaultQuotaGuard guard;
    private final ObjectProvider<TokenUsageSumPort> usageSum;

    public QuotaReconciler(StringRedisTemplate redis,
                           GovQuotaRepository quotas,
                           DefaultQuotaGuard guard,
                           ObjectProvider<TokenUsageSumPort> usageSum) {
        this.redis = redis;
        this.quotas = quotas;
        this.guard = guard;
        this.usageSum = usageSum;
    }

    @Scheduled(cron = "${tioka.quota.reconcile-cron:0 15 * * * *}")
    public void reconcile() {
        TokenUsageSumPort port = usageSum.getIfAvailable();
        if (port == null) {
            return;
        }
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        Instant from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String yyyyMM = DefaultQuotaGuard.yearMonth();
        List<GovQuotaDO> rows = quotas.listByMetric(QuotaMetrics.TOKENS_MONTH);
        for (GovQuotaDO row : rows) {
            long fromUsage = port.sumTokens(row.getScopeType(), row.getScopeId(), from, to);
            String key = guard.tokKey(row.getScopeType(), row.getScopeId(), yyyyMM);
            long fromRedis = 0L;
            String v = redis.opsForValue().get(key);
            if (v != null) {
                try {
                    fromRedis = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    fromRedis = 0L;
                }
            }
            long corrected = Math.max(fromUsage, fromRedis);
            if (corrected != fromRedis) {
                redis.opsForValue().set(key, String.valueOf(corrected), DefaultQuotaGuard.TOKEN_TTL);
                log.info("QuotaReconciler {}/{} redis {} → {} (usage={})",
                        row.getScopeType(), row.getScopeId(), fromRedis, corrected, fromUsage);
            }
        }
    }
}

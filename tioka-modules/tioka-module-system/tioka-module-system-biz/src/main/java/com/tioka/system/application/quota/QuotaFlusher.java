package com.tioka.system.application.quota;

import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 每 60 秒把本月 Redis token 计数写回 {@code gov_quota.used_value}。
 */
@Component
public class QuotaFlusher {
    private static final Logger log = LoggerFactory.getLogger(QuotaFlusher.class);

    private final StringRedisTemplate redis;
    private final GovQuotaRepository quotas;
    private final DefaultQuotaGuard guard;

    public QuotaFlusher(StringRedisTemplate redis, GovQuotaRepository quotas, DefaultQuotaGuard guard) {
        this.redis = redis;
        this.quotas = quotas;
        this.guard = guard;
    }

    @Scheduled(fixedDelayString = "${tioka.quota.flush-interval-ms:60000}")
    public void flush() {
        String ym = DefaultQuotaGuard.yearMonth();
        OffsetDateTime windowStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<GovQuotaDO> rows = quotas.listByMetric(QuotaMetrics.TOKENS_MONTH);
        int n = 0;
        for (GovQuotaDO row : rows) {
            String key = guard.tokKey(row.getScopeType(), row.getScopeId(), ym);
            String v = redis.opsForValue().get(key);
            long used;
            if (v == null) {
                used = row.getUsedValue() == null ? 0L : row.getUsedValue();
            } else {
                try {
                    used = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            quotas.updateUsed(row.getScopeType(), row.getScopeId(), QuotaMetrics.TOKENS_MONTH,
                    used, windowStart, "quota-flusher");
            n++;
        }
        if (n > 0) {
            log.debug("QuotaFlusher wrote {} token rows for {}", n, ym);
        }
    }
}

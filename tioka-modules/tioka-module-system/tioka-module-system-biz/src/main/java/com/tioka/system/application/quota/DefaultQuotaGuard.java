package com.tioka.system.application.quota;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.QuotaGuard;
import com.tioka.system.api.spi.QuotaThresholdExceededEvent;
import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 配额计数 + {@code gov_quota} 上限（M1-03 GOV-03）+ 月度阈值预警（P2 / GAP-L5-3）。
 */
@Component
public class DefaultQuotaGuard implements QuotaGuard {
    private static final Logger log = LoggerFactory.getLogger(DefaultQuotaGuard.class);

    static final Duration LIMIT_TTL = Duration.ofSeconds(60);
    static final Duration TOKEN_TTL = Duration.ofDays(40);
    /** 月度 token 预警阈值（百分比）；越过即发 {@link QuotaThresholdExceededEvent}。 */
    static final int WARN_THRESHOLD_PCT = 80;
    private static final String LIM_MISS = "__none__";

    /**
     * 原子占位：清理过期 → 校验 ZCARD → ZADD；KEYS 为各 scope 的 q:cc 键，ARGV=now,turnId,exp,lim1,lim2,...
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local now = tonumber(ARGV[1])
            local turnId = ARGV[2]
            local exp = tonumber(ARGV[3])
            for i = 1, #KEYS do
              local lim = tonumber(ARGV[3 + i])
              redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
              if redis.call('ZCARD', KEYS[i]) >= lim then
                return 0
              end
            end
            for i = 1, #KEYS do
              redis.call('ZADD', KEYS[i], exp, turnId)
            end
            return 1
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final GovQuotaRepository quotas;
    private final ApplicationEventPublisher events;

    public DefaultQuotaGuard(StringRedisTemplate redis, GovQuotaRepository quotas,
                             ApplicationEventPublisher events) {
        this.redis = redis;
        this.quotas = quotas;
        this.events = events;
    }

    @Override
    public void precheckTokens(String workspaceId, String userId, String agentId) {
        checkScope(QuotaMetrics.SCOPE_WORKSPACE, workspaceId);
        checkScope(QuotaMetrics.SCOPE_USER, userId);
        checkScope(QuotaMetrics.SCOPE_AGENT, agentId);
    }

    @Override
    public void consumeTokens(String workspaceId, String userId, String agentId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        String ym = yearMonth();
        incrTok(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym, tokens);
        incrTok(QuotaMetrics.SCOPE_USER, userId, ym, tokens);
        incrTok(QuotaMetrics.SCOPE_AGENT, agentId, ym, tokens);
        // P2（GAP-L5-3）：计数后对三个 scope 做月度阈值预警（首次越线才发）
        warnIfCrossed(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, ym);
        warnIfCrossed(QuotaMetrics.SCOPE_USER, userId, ym);
        warnIfCrossed(QuotaMetrics.SCOPE_AGENT, agentId, ym);
    }

    /**
     * 月度阈值预警：已用量首次越过 {@link #WARN_THRESHOLD_PCT}% 上限时发布领域事件。
     * 去重键走 Redis SETNX（TTL 同 token 计数，覆盖整月窗口）；
     * 预警失败只记日志，不阻断计数主流程。
     */
    private void warnIfCrossed(String scopeType, String scopeId, String ym) {
        if (blank(scopeId)) {
            return;
        }
        Long lim = limitOf(scopeType, scopeId, QuotaMetrics.TOKENS_MONTH);
        if (lim == null || lim <= 0) {
            return;
        }
        long used = usedTokens(scopeType, scopeId);
        if (used * 100 < lim * WARN_THRESHOLD_PCT) {
            return;
        }
        String dedupKey = warnKey(scopeType, scopeId, ym);
        try {
            Boolean first = redis.opsForValue()
                    .setIfAbsent(dedupKey, "1", TOKEN_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
            events.publishEvent(new QuotaThresholdExceededEvent(
                    scopeType, scopeId, QuotaMetrics.TOKENS_MONTH,
                    lim, used, WARN_THRESHOLD_PCT, ym));
            log.info("quota warning published: {}/{}/{} used={} limit={} pct={}",
                    scopeType, scopeId, ym, used, lim, WARN_THRESHOLD_PCT);
        } catch (RuntimeException e) {
            log.warn("quota warning failed (ignored): {}/{}: {}", scopeType, scopeId, e.toString());
        }
    }

    /** 预警去重键：{@code q:warn:{scopeType}:{scopeId}:{yyyyMM}:80}。 */
    public String warnKey(String scopeType, String scopeId, String yyyyMM) {
        return "q:warn:" + scopeType + ":" + scopeId + ":" + yyyyMM + ":" + WARN_THRESHOLD_PCT;
    }

    @Override
    public boolean tryAcquireConcurrent(String workspaceId, String userId, String turnId, Instant expiresAt) {
        if (turnId == null || turnId.isBlank() || expiresAt == null) {
            return true;
        }
        List<String> keys = new ArrayList<>(2);
        List<String> lims = new ArrayList<>(2);
        collectConcurrentScope(keys, lims, QuotaMetrics.SCOPE_WORKSPACE, workspaceId);
        collectConcurrentScope(keys, lims, QuotaMetrics.SCOPE_USER, userId);
        if (keys.isEmpty()) {
            return true;
        }
        List<String> argv = new ArrayList<>(3 + lims.size());
        argv.add(String.valueOf(Instant.now().toEpochMilli()));
        argv.add(turnId);
        argv.add(String.valueOf(expiresAt.toEpochMilli()));
        argv.addAll(lims);
        try {
            Long ok = redis.execute(ACQUIRE_SCRIPT, keys, argv.toArray(new String[0]));
            return ok != null && ok == 1L;
        } catch (RuntimeException e) {
            log.warn("concurrent acquire lua failed, fallback turnId={}: {}", turnId, e.toString());
            return tryAcquireConcurrentFallback(keys, lims, turnId, expiresAt);
        }
    }

    private void collectConcurrentScope(List<String> keys, List<String> lims,
                                        String scopeType, String scopeId) {
        long lim = limitOrUnlimited(scopeType, scopeId, QuotaMetrics.CONCURRENT_TURNS);
        if (lim < 0 || blank(scopeId)) {
            return;
        }
        keys.add(ccKey(scopeType, scopeId));
        lims.add(String.valueOf(lim));
    }

    /** Lua 不可用时的降级：逐 key 清理/校验/写入，失败回滚已写入。 */
    private boolean tryAcquireConcurrentFallback(List<String> keys, List<String> lims,
                                                 String turnId, Instant expiresAt) {
        long now = Instant.now().toEpochMilli();
        double exp = expiresAt.toEpochMilli();
        List<String> added = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            long lim = Long.parseLong(lims.get(i));
            redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
            Long card = redis.opsForZSet().zCard(key);
            if (card != null && card >= lim) {
                for (String a : added) {
                    redis.opsForZSet().remove(a, turnId);
                }
                return false;
            }
            redis.opsForZSet().add(key, turnId, exp);
            added.add(key);
        }
        return true;
    }

    @Override
    public void renewConcurrent(String workspaceId, String userId, String turnId, Instant expiresAt) {
        if (turnId == null || expiresAt == null) {
            return;
        }
        double score = expiresAt.toEpochMilli();
        if (limitOrUnlimited(QuotaMetrics.SCOPE_WORKSPACE, workspaceId, QuotaMetrics.CONCURRENT_TURNS) >= 0) {
            redis.opsForZSet().add(ccKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId), turnId, score);
        }
        if (limitOrUnlimited(QuotaMetrics.SCOPE_USER, userId, QuotaMetrics.CONCURRENT_TURNS) >= 0) {
            redis.opsForZSet().add(ccKey(QuotaMetrics.SCOPE_USER, userId), turnId, score);
        }
    }

    @Override
    public void releaseConcurrent(String workspaceId, String userId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        redis.opsForZSet().remove(ccKey(QuotaMetrics.SCOPE_WORKSPACE, workspaceId), turnId);
        redis.opsForZSet().remove(ccKey(QuotaMetrics.SCOPE_USER, userId), turnId);
    }

    /** 实时 used（Redis）；缺失时从 DB 恢复。 */
    public long usedTokens(String scopeType, String scopeId) {
        if (blank(scopeId)) {
            return 0L;
        }
        String key = tokKey(scopeType, scopeId, yearMonth());
        String v = redis.opsForValue().get(key);
        if (v != null) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return restoreTokFromDb(scopeType, scopeId, key);
    }

    public Long limitOf(String scopeType, String scopeId, String metric) {
        if (blank(scopeType) || blank(scopeId) || blank(metric)) {
            return null;
        }
        String limKey = limKey(scopeType, scopeId, metric);
        String cached = redis.opsForValue().get(limKey);
        if (LIM_MISS.equals(cached)) {
            return null;
        }
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        GovQuotaDO row = quotas.find(scopeType, scopeId, metric);
        if (row == null || row.getLimitValue() == null) {
            redis.opsForValue().set(limKey, LIM_MISS, LIMIT_TTL);
            return null;
        }
        redis.opsForValue().set(limKey, String.valueOf(row.getLimitValue()), LIMIT_TTL);
        return row.getLimitValue();
    }

    /** PUT/DELETE 后清上限缓存。 */
    public void invalidateLimitCache(String scopeType, String scopeId, String metric) {
        redis.delete(limKey(scopeType, scopeId, metric));
    }

    public String tokKey(String scopeType, String scopeId, String yyyyMM) {
        return "q:tok:" + scopeType + ":" + scopeId + ":" + yyyyMM;
    }

    public String limKey(String scopeType, String scopeId, String metric) {
        return "q:lim:" + scopeType + ":" + scopeId + ":" + metric;
    }

    public String ccKey(String scopeType, String scopeId) {
        return "q:cc:" + scopeType + ":" + (scopeId == null ? "_" : scopeId);
    }

    public static String yearMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString().replace("-", "");
    }

    private void checkScope(String scopeType, String scopeId) {
        Long lim = limitOf(scopeType, scopeId, QuotaMetrics.TOKENS_MONTH);
        if (lim == null) {
            return;
        }
        long used = usedTokens(scopeType, scopeId);
        if (used >= lim) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED,
                    "配额已用尽: " + scopeType + "/" + scopeId + " tokens_month");
        }
    }

    private long limitOrUnlimited(String scopeType, String scopeId, String metric) {
        Long lim = limitOf(scopeType, scopeId, metric);
        return lim == null ? -1L : Math.max(0L, lim);
    }

    private void incrTok(String scopeType, String scopeId, String ym, long tokens) {
        if (blank(scopeId)) {
            return;
        }
        String key = tokKey(scopeType, scopeId, ym);
        // Redis 丢失时先从 DB 灌回，避免计数从 0 重算
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            restoreTokFromDb(scopeType, scopeId, key);
        }
        Long n = redis.opsForValue().increment(key, tokens);
        if (n != null && n == tokens) {
            redis.expire(key, TOKEN_TTL);
        }
    }

    private long restoreTokFromDb(String scopeType, String scopeId, String redisKey) {
        GovQuotaDO row = quotas.find(scopeType, scopeId, QuotaMetrics.TOKENS_MONTH);
        long used = row == null || row.getUsedValue() == null ? 0L : Math.max(0L, row.getUsedValue());
        Boolean set = redis.opsForValue().setIfAbsent(redisKey, String.valueOf(used), TOKEN_TTL);
        if (Boolean.FALSE.equals(set)) {
            String v = redis.opsForValue().get(redisKey);
            if (v != null) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return used;
                }
            }
        }
        log.debug("restored token counter {}={} from gov_quota", redisKey, used);
        return used;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

package com.tioka.model.application.gateway;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MH-03：同 binding 指数退避。{@code delay = base × 2^n × (0.8〜1.2)}；429 优先 {@code Retry-After}（封顶 10s）。
 */
public final class RetryBackoff {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration budget;
    private final long retryAfterCapMs;

    public RetryBackoff(int maxAttempts, Duration baseDelay, Duration budget) {
        this.maxAttempts = Math.max(0, maxAttempts);
        this.baseDelay = baseDelay == null ? Duration.ofMillis(500) : baseDelay;
        this.budget = budget == null ? Duration.ofSeconds(20) : budget;
        this.retryAfterCapMs = 10_000L;
    }

    /** 配置项 {@code tioka.model.retry.max-attempts}：最多重试次数（不含首次）。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration budget() {
        return budget;
    }

    /**
     * @param attemptIndex 第几次重试，从 0 起（首次失败后的第一次重试为 0）
     * @param retryAfterMs 上游 Retry-After（毫秒）；可空
     * @param spentMs      本轮已花费的退避合计
     * @return 应睡眠毫秒；若超预算返回 -1（不再重试）
     */
    public long delayMs(int attemptIndex, Long retryAfterMs, long spentMs) {
        if (attemptIndex < 0 || attemptIndex >= maxAttempts) {
            return -1L;
        }
        long budgetMs = Math.max(0L, budget.toMillis());
        if (spentMs >= budgetMs) {
            return -1L;
        }
        long raw;
        if (retryAfterMs != null && retryAfterMs > 0) {
            raw = Math.min(retryAfterMs, retryAfterCapMs);
        } else {
            long base = Math.max(1L, baseDelay.toMillis());
            double factor = Math.pow(2.0, attemptIndex);
            double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
            raw = Math.round(base * factor * jitter);
        }
        long remain = budgetMs - spentMs;
        if (remain <= 0) {
            return -1L;
        }
        return Math.min(raw, remain);
    }
}

package com.tioka.connector.api.sdk;

/**
 * 可重试错误的退避策略（M3-06：指数退避最多 3 次）。
 */
public record ConnectorRetryPolicy(
        int maxAttempts,
        long initialBackoffMs,
        double multiplier
) {
    public static final ConnectorRetryPolicy DEFAULT = new ConnectorRetryPolicy(3, 50L, 2.0);

    public ConnectorRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts >= 1");
        }
        if (initialBackoffMs < 0) {
            throw new IllegalArgumentException("initialBackoffMs >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier >= 1");
        }
    }

    public long backoffMs(int zeroBasedAttempt) {
        return Math.round(initialBackoffMs * Math.pow(multiplier, Math.max(0, zeroBasedAttempt)));
    }
}

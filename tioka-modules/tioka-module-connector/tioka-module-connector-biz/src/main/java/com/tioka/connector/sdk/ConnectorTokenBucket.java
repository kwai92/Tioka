package com.tioka.connector.sdk;

/**
 * 实例级令牌桶（内存）。capacity = max(1, ceil(qps))，按 qps 补令牌。
 */
final class ConnectorTokenBucket {

    private final double capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastRefillNanos;

    ConnectorTokenBucket(double qps) {
        this.capacity = Math.max(1.0, Math.ceil(qps));
        this.refillPerSec = Math.max(0.1, qps);
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
        lastRefillNanos = now;
    }
}

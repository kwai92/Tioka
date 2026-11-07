package com.tioka.model.application.gateway;

import com.tioka.model.infrastructure.provider.ProviderHttpException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-03：指数退避与 Retry-After 公式（BackoffIT 的单元侧）。
 */
class RetryBackoffTest {

    @Test
    void exponentialWithJitterWithinTwentyPercent() {
        RetryBackoff backoff = new RetryBackoff(2, Duration.ofMillis(500), Duration.ofSeconds(20));
        for (int i = 0; i < 40; i++) {
            long d0 = backoff.delayMs(0, null, 0);
            assertTrue(d0 >= 400 && d0 <= 600, "n=0 应为 500±20%: " + d0);
            long d1 = backoff.delayMs(1, null, 0);
            assertTrue(d1 >= 800 && d1 <= 1200, "n=1 应为 1000±20%: " + d1);
        }
        assertEquals(-1L, backoff.delayMs(2, null, 0), "超过 max-attempts 不再退避");
    }

    @Test
    void retryAfterPreferredAndCappedAt10s() {
        RetryBackoff backoff = new RetryBackoff(2, Duration.ofMillis(500), Duration.ofSeconds(20));
        assertEquals(3000L, backoff.delayMs(0, 3000L, 0));
        assertEquals(10_000L, backoff.delayMs(0, 60_000L, 0), "Retry-After 最长 10s");
    }

    @Test
    void budgetStopsFurtherRetries() {
        RetryBackoff backoff = new RetryBackoff(2, Duration.ofMillis(500), Duration.ofSeconds(1));
        assertEquals(-1L, backoff.delayMs(0, null, 1000), "已耗尽 budget");
        long remain = backoff.delayMs(0, 5000L, 800);
        assertTrue(remain > 0 && remain <= 200, "剩余 budget 截断: " + remain);
    }

    @Test
    void parseRetryAfterSeconds() {
        assertEquals(5000L, ProviderHttpException.parseRetryAfterMs("5"));
        assertNull(ProviderHttpException.parseRetryAfterMs("not-a-number"));
        assertNull(ProviderHttpException.parseRetryAfterMs(null));
    }
}

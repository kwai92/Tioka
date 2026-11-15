package com.tioka.model.application.health;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-10（ADR-041 第 5.1 节）：滚动窗口聚合与分桶近似百分位单测。
 */
class WindowStatsTest {

    private static Map<Object, Object> bucket(Object... kv) {
        Map<Object, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void emptyIsAllZero() {
        WindowStats w = WindowStats.empty();
        assertEquals(0L, w.calls());
        assertEquals(0d, w.errorRate());
        assertEquals(0d, w.ratio429());
        assertEquals(0d, w.avgLatencyMs());
        assertEquals(0d, w.avgTtftMs());
        assertEquals(0d, w.percentileLatencyMs(0.95));
    }

    @Test
    void mergeSumsCountersAndHistograms() {
        WindowStats w = WindowStats.merge(List.of(
                bucket("calls", "10", "err_429", "2", "err_5xx", "1", "lat_sum", "1000", "lat_cnt", "10",
                        "tok_in", "100", "tok_out", "50", "cost_micros", "700", "h1", "3"),
                bucket("calls", "5", "err_timeout", "1", "lat_sum", "500", "lat_cnt", "5",
                        "tok_in", "20", "h1", "2", "h8", "1")));
        assertEquals(15L, w.calls());
        assertEquals(4L, w.errors());
        assertEquals(2L, w.err429());
        assertEquals(1L, w.err5xx());
        assertEquals(1L, w.errTimeout());
        assertEquals(1500L, w.latencySum());
        assertEquals(15L, w.latencyCount());
        assertEquals(100d, w.avgLatencyMs());
        assertEquals(120L, w.tokensIn());
        assertEquals(50L, w.tokensOut());
        assertEquals(700L, w.costMicros());
        assertEquals(5L, w.latencyHistogram()[1]);
        assertEquals(1L, w.latencyHistogram()[8]);
    }

    @Test
    void ratesMath() {
        WindowStats w = WindowStats.merge(List.of(
                bucket("calls", "10", "err_429", "3", "err_other", "2")));
        assertEquals(0.5d, w.errorRate());
        assertEquals(0.3d, w.ratio429());
    }

    @Test
    void percentileIsBucketUpperBound() {
        // 10 个样本：h0=9（<=50ms）、h3=1（<=500ms）→ P95 应落在 500 桶上界
        WindowStats w = WindowStats.merge(List.of(
                bucket("calls", "10", "lat_sum", "0", "lat_cnt", "10", "h0", "9", "h3", "1")));
        assertEquals(500d, w.percentileLatencyMs(0.95));
        assertEquals(50d, w.percentileLatencyMs(0.50));
    }

    @Test
    void ttftPercentileIsBucketUpperBound() {
        WindowStats w = WindowStats.merge(List.of(
                bucket("calls", "10", "ttft_sum", "0", "ttft_cnt", "10", "th2", "10")));
        assertEquals(1_000d, w.percentileTtftMs(0.95));
    }

    @Test
    void bucketIndexBoundaries() {
        assertEquals(0, WindowStats.bucketIndex(50L));
        assertEquals(1, WindowStats.bucketIndex(51L));
        assertEquals(1, WindowStats.bucketIndex(100L));
        assertEquals(8, WindowStats.bucketIndex(10_001L));
        assertEquals(0, WindowStats.ttftBucketIndex(200L));
        assertEquals(1, WindowStats.ttftBucketIndex(201L));
        assertEquals(8, WindowStats.ttftBucketIndex(30_001L));
    }

    @Test
    void mergeIgnoresNullAndEmpty() {
        WindowStats w = WindowStats.merge(java.util.Arrays.asList(null, bucket()));
        assertEquals(0L, w.calls());
        assertTrue(w.percentileLatencyMs(0.99) == 0d);
    }
}

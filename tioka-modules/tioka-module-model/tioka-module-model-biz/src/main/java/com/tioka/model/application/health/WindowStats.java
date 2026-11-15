package com.tioka.model.application.health;

import java.util.Collection;
import java.util.Map;

/**
 * MH-10（ADR-041 第 5.1 节）：单个作用对象在一个滚动窗口内的聚合统计。
 *
 * <p>由 Redis 分桶哈希（{@link ModelCallStatsWindow}）合并而来。延迟与首 token 延迟各用固定分桶
 * 近似百分位（口径：返回首个累计计数达到目标分位的桶上界）。
 */
public record WindowStats(long calls,
                          long err429,
                          long err5xx,
                          long errTimeout,
                          long errAuth,
                          long errOther,
                          long latencySum,
                          long latencyCount,
                          long ttftSum,
                          long ttftCount,
                          long tokensIn,
                          long tokensOut,
                          long tokensCached,
                          long costMicros,
                          long[] latencyHistogram,
                          long[] ttftHistogram) {

    /** 延迟分桶上界（毫秒）；最后一桶为 +Inf。 */
    public static final long[] LATENCY_BOUNDS_MS = {50L, 100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L};
    /** 首 token 延迟分桶上界（毫秒）；最后一桶为 +Inf。 */
    public static final long[] TTFT_BOUNDS_MS = {200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L};

    /** 空统计。 */
    public static WindowStats empty() {
        return new WindowStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new long[LATENCY_BOUNDS_MS.length + 1], new long[TTFT_BOUNDS_MS.length + 1]);
    }

    /** 把若干 Redis 分桶哈希合并为一份统计。 */
    public static WindowStats merge(Collection<Map<Object, Object>> buckets) {
        long calls = 0, e429 = 0, e5xx = 0, eTimeout = 0, eAuth = 0, eOther = 0;
        long latSum = 0, latCnt = 0, ttftSum = 0, ttftCnt = 0;
        long in = 0, out = 0, cached = 0, cost = 0;
        long[] latHist = new long[LATENCY_BOUNDS_MS.length + 1];
        long[] ttftHist = new long[TTFT_BOUNDS_MS.length + 1];
        if (buckets != null) {
            for (Map<Object, Object> b : buckets) {
                if (b == null || b.isEmpty()) {
                    continue;
                }
                calls += num(b, "calls");
                e429 += num(b, "err_429");
                e5xx += num(b, "err_5xx");
                eTimeout += num(b, "err_timeout");
                eAuth += num(b, "err_auth");
                eOther += num(b, "err_other");
                latSum += num(b, "lat_sum");
                latCnt += num(b, "lat_cnt");
                ttftSum += num(b, "ttft_sum");
                ttftCnt += num(b, "ttft_cnt");
                in += num(b, "tok_in");
                out += num(b, "tok_out");
                cached += num(b, "tok_cached");
                cost += num(b, "cost_micros");
                for (int i = 0; i < latHist.length; i++) {
                    latHist[i] += num(b, "h" + i);
                }
                for (int i = 0; i < ttftHist.length; i++) {
                    ttftHist[i] += num(b, "th" + i);
                }
            }
        }
        return new WindowStats(calls, e429, e5xx, eTimeout, eAuth, eOther,
                latSum, latCnt, ttftSum, ttftCnt, in, out, cached, cost, latHist, ttftHist);
    }

    private static long num(Map<Object, Object> m, String field) {
        Object v = m.get(field);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 总错误数。 */
    public long errors() {
        return err429 + err5xx + errTimeout + errAuth + errOther;
    }

    /** 错误率（无调用记 0）。 */
    public double errorRate() {
        return calls <= 0 ? 0d : (double) errors() / calls;
    }

    /** 429 占比（无调用记 0）。 */
    public double ratio429() {
        return calls <= 0 ? 0d : (double) err429 / calls;
    }

    /** 平均延迟（毫秒；无样本记 0）。 */
    public double avgLatencyMs() {
        return latencyCount <= 0 ? 0d : (double) latencySum / latencyCount;
    }

    /** 平均首 token 延迟（毫秒；无样本记 0）。 */
    public double avgTtftMs() {
        return ttftCount <= 0 ? 0d : (double) ttftSum / ttftCount;
    }

    /** 延迟近似百分位（毫秒）；无样本记 0。 */
    public double percentileLatencyMs(double p) {
        return percentile(latencyHistogram, LATENCY_BOUNDS_MS, latencyCount, p);
    }

    /** 首 token 延迟近似百分位（毫秒）；无样本记 0。 */
    public double percentileTtftMs(double p) {
        return percentile(ttftHistogram, TTFT_BOUNDS_MS, ttftCount, p);
    }

    private static double percentile(long[] hist, long[] bounds, long count, double p) {
        if (count <= 0 || p <= 0d) {
            return 0d;
        }
        double target = Math.min(1d, p) * count;
        long cum = 0;
        for (int i = 0; i < hist.length; i++) {
            cum += hist[i];
            if (cum >= target) {
                return i < bounds.length ? (double) bounds[i] : bounds[bounds.length - 1];
            }
        }
        return bounds[bounds.length - 1];
    }

    /** 延迟值落入的延迟分桶下标。 */
    public static int bucketIndex(long latencyMs) {
        return index(LATENCY_BOUNDS_MS, latencyMs);
    }

    /** 首 token 延迟值落入的分桶下标。 */
    public static int ttftBucketIndex(long ttftMs) {
        return index(TTFT_BOUNDS_MS, ttftMs);
    }

    private static int index(long[] bounds, long value) {
        for (int i = 0; i < bounds.length; i++) {
            if (value <= bounds[i]) {
                return i;
            }
        }
        return bounds.length;
    }
}

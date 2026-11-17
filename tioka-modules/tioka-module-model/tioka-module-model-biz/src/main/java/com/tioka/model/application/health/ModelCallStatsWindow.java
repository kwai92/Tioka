package com.tioka.model.application.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MH-10（ADR-041 第 5.1 节）：模型调用滚动窗口统计（Redis）。
 *
 * <p>每个作用对象（供应商 / 绑定 / 别名）各记一份：最近 60 分钟按 1 分钟分桶、最近 24 小时按 5 分钟分桶；
 * 桶内记调用数、按类别错误数（429 / 5xx / 超时 / 鉴权失败 / 其他）、延迟直方图、首 token 延迟、
 * token 数与成本。分桶键带 TTL，过期即滑出窗口。
 *
 * <p>Redis 缺席（{@code ObjectProvider} 无实例）时全部降级为空操作 / 空统计，不影响模型调用。
 */
@Component
public class ModelCallStatsWindow {

    private static final Logger log = LoggerFactory.getLogger(ModelCallStatsWindow.class);

    /** 维度名。 */
    public static final String DIM_PROVIDER = "provider";
    public static final String DIM_BINDING = "binding";
    public static final String DIM_ALIAS = "alias";

    /** 1 分钟分桶窗口长度（60 个桶）。 */
    static final int MINUTE_BUCKETS = 60;
    /** 5 分钟分桶窗口长度（288 个桶 === 24 小时）。 */
    static final int FIVE_MIN_BUCKETS = 288;
    /** 1 分钟桶 TTL（秒）。 */
    static final int MINUTE_TTL_SECONDS = 90 * 60;
    /** 5 分钟桶 TTL（秒）。 */
    static final int FIVE_MIN_TTL_SECONDS = 26 * 3600;
    /** 最近错误样本 TTL（秒）。 */
    static final int ERROR_SAMPLE_TTL_SECONDS = 24 * 3600;
    /** 错误消息截断长度。 */
    static final int MESSAGE_MAX = 500;

    private final ObjectProvider<StringRedisTemplate> redis;

    public ModelCallStatsWindow(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    /** 记录一次调用（失败只记日志）。 */
    public void record(ModelCallSample s) {
        if (s == null) {
            return;
        }
        StringRedisTemplate t = redis == null ? null : redis.getIfAvailable();
        if (t == null) {
            return;
        }
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Long> deltas = deltas(s);
            for (String[] dim : dimsOf(s)) {
                write(t, dim[0], dim[1], now, deltas);
                if (!s.success()) {
                    writeErrorSample(t, dim[0], dim[1], s);
                }
            }
        } catch (RuntimeException e) {
            log.warn("model call stats record failed (ignored): {}", e.toString());
        }
    }

    /** 近 60 分钟统计（1 分钟分桶）。 */
    public WindowStats last60m(String dim, String id) {
        return read(dim, id, "1m", MINUTE_BUCKETS, 60);
    }

    /** 近 N 分钟统计（1 分钟分桶；用于短窗错误率 / P95）。 */
    public WindowStats lastMinutes(String dim, String id, int minutes) {
        int n = Math.max(1, Math.min(minutes, MINUTE_BUCKETS));
        return read(dim, id, "1m", n, 60);
    }

    /** 近 24 小时统计（5 分钟分桶）。 */
    public WindowStats last24h(String dim, String id) {
        return read(dim, id, "5m", FIVE_MIN_BUCKETS, 300);
    }

    /** 最近一次错误样本；无则 null。 */
    public ErrorSample lastError(String dim, String id) {
        StringRedisTemplate t = redis == null ? null : redis.getIfAvailable();
        if (t == null || blank(id)) {
            return null;
        }
        try {
            Map<Object, Object> m = t.opsForHash().entries(errorKey(dim, id));
            if (m == null || m.isEmpty()) {
                return null;
            }
            return new ErrorSample(str(m, "error_class"), str(m, "message"),
                    parseLong(m, "ts"), str(m, "trace_id"));
        } catch (RuntimeException e) {
            log.warn("read last error failed: {}", e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- internals

    private Map<String, Long> deltas(ModelCallSample s) {
        Map<String, Long> d = new LinkedHashMap<>();
        d.put("calls", 1L);
        if (!s.success()) {
            d.put("err_" + normalizeErrorClass(s.errorClass()), 1L);
        }
        long lat = Math.max(0L, s.latencyMs());
        d.put("lat_sum", lat);
        d.put("lat_cnt", 1L);
        d.put("h" + WindowStats.bucketIndex(lat), 1L);
        if (s.ttftMs() != null) {
            long ttft = Math.max(0L, s.ttftMs());
            d.put("ttft_sum", ttft);
            d.put("ttft_cnt", 1L);
            d.put("th" + WindowStats.ttftBucketIndex(ttft), 1L);
        }
        d.put("tok_in", (long) Math.max(0, s.tokensIn()));
        d.put("tok_out", (long) Math.max(0, s.tokensOut()));
        d.put("tok_cached", (long) Math.max(0, s.tokensCached()));
        d.put("cost_micros", Math.max(0L, s.costMicros()));
        return d;
    }

    private static List<String[]> dimsOf(ModelCallSample s) {
        List<String[]> dims = new ArrayList<>(3);
        addIfPresent(dims, DIM_PROVIDER, s.providerId());
        addIfPresent(dims, DIM_BINDING, s.bindingId());
        addIfPresent(dims, DIM_ALIAS, s.aliasId());
        return dims;
    }

    private static void addIfPresent(List<String[]> dims, String dim, String id) {
        if (!blank(id)) {
            dims.add(new String[]{dim, id});
        }
    }

    private void write(StringRedisTemplate t, String dim, String id, long now, Map<String, Long> deltas) {
        String k1 = statKey(dim, id, "1m", now / 60);
        String k5 = statKey(dim, id, "5m", now / 300);
        t.executePipelined((RedisCallback<Object>) conn -> {
            incrAll(conn, k1, MINUTE_TTL_SECONDS, deltas);
            incrAll(conn, k5, FIVE_MIN_TTL_SECONDS, deltas);
            return null;
        });
    }

    private static void incrAll(RedisConnection conn, String key, int ttlSeconds, Map<String, Long> deltas) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, Long> e : deltas.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0L) {
                continue;
            }
            conn.hashCommands().hIncrBy(kb, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
        }
        conn.keyCommands().expire(kb, ttlSeconds);
    }

    private void writeErrorSample(StringRedisTemplate t, String dim, String id, ModelCallSample s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error_class", normalizeErrorClass(s.errorClass()));
        m.put("message", truncate(s.lastErrorMessage()));
        m.put("ts", String.valueOf(System.currentTimeMillis()));
        m.put("trace_id", s.traceId() == null ? "" : s.traceId());
        String key = errorKey(dim, id);
        t.opsForHash().putAll(key, m);
        t.expire(key, java.time.Duration.ofSeconds(ERROR_SAMPLE_TTL_SECONDS));
    }

    private WindowStats read(String dim, String id, String res, int bucketCount, int bucketSeconds) {
        StringRedisTemplate t = redis == null ? null : redis.getIfAvailable();
        if (t == null || blank(id)) {
            return WindowStats.empty();
        }
        try {
            long cur = Instant.now().getEpochSecond() / bucketSeconds;
            List<Map<Object, Object>> buckets = new ArrayList<>(bucketCount);
            for (long b = cur - (bucketCount - 1); b <= cur; b++) {
                Map<Object, Object> m = t.opsForHash().entries(statKey(dim, id, res, b));
                if (m != null && !m.isEmpty()) {
                    buckets.add(m);
                }
            }
            return WindowStats.merge(buckets);
        } catch (RuntimeException e) {
            log.warn("read window stats failed: {}", e.toString());
            return WindowStats.empty();
        }
    }

    static String statKey(String dim, String id, String res, long bucket) {
        return "mh:stat:" + dim + ":" + id + ":" + res + ":" + bucket;
    }

    static String errorKey(String dim, String id) {
        return "mh:lasterr:" + dim + ":" + id;
    }

    /** 错误类别归一化到桶字段后缀。 */
    static String normalizeErrorClass(String errorClass) {
        if (errorClass == null || errorClass.isBlank()) {
            return "other";
        }
        String c = errorClass.trim().toLowerCase();
        return switch (c) {
            case "429" -> "429";
            case "5xx" -> "5xx";
            case "timeout" -> "timeout";
            case "auth" -> "auth";
            default -> "other";
        };
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MESSAGE_MAX ? s : s.substring(0, MESSAGE_MAX);
    }

    private static String str(Map<Object, Object> m, String field) {
        Object v = m.get(field);
        return v == null ? null : String.valueOf(v);
    }

    private static long parseLong(Map<Object, Object> m, String field) {
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 最近一次错误样本。 */
    public record ErrorSample(String errorClass, String message, long epochMs, String traceId) {
    }
}

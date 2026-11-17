package com.tioka.model.application.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MH-10（ADR-041 第 5.1 节）：主动探测状态（连续失败计数 + 最近一次探测结论）。
 *
 * <p>供 {@link ModelHealthProbe} 写入、{@link ModelHealthService} 读取；只存 Redis，不落库。
 * Redis 缺席时降级：连续失败恒为 0、最近结论为空。
 */
@Component
public class ModelProbeStore {

    private static final Logger log = LoggerFactory.getLogger(ModelProbeStore.class);

    /** 连续失败计数保留时长。 */
    static final Duration FAIL_TTL = Duration.ofHours(24);
    /** 最近探测结论保留时长。 */
    static final Duration LAST_TTL = Duration.ofHours(24);

    private final ObjectProvider<StringRedisTemplate> redis;

    public ModelProbeStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    /** 连续失败次数（无记录记 0）。 */
    public int consecutiveFailures(String providerId) {
        StringRedisTemplate t = t();
        if (t == null || blank(providerId)) {
            return 0;
        }
        try {
            String v = t.opsForValue().get(failKey(providerId));
            return v == null ? 0 : Math.max(0, Integer.parseInt(v));
        } catch (RuntimeException e) {
            log.warn("read probe failures failed providerId={}: {}", providerId, e.toString());
            return 0;
        }
    }

    /** 探测成功：清零连续失败计数并记结论。 */
    public void recordSuccess(String providerId, long epochMs) {
        StringRedisTemplate t = t();
        if (t == null || blank(providerId)) {
            return;
        }
        try {
            t.delete(failKey(providerId));
            t.opsForHash().putAll(lastKey(providerId), last(1, epochMs, null));
            t.expire(lastKey(providerId), LAST_TTL);
        } catch (RuntimeException e) {
            log.warn("record probe success failed providerId={}: {}", providerId, e.toString());
        }
    }

    /** 探测失败：连续失败计数 + 1 并记结论。 */
    public void recordFailure(String providerId, String message, long epochMs) {
        StringRedisTemplate t = t();
        if (t == null || blank(providerId)) {
            return;
        }
        try {
            String fk = failKey(providerId);
            Long n = t.opsForValue().increment(fk);
            if (n != null && n == 1L) {
                t.expire(fk, FAIL_TTL);
            }
            t.opsForHash().putAll(lastKey(providerId), last(0, epochMs, message));
            t.expire(lastKey(providerId), LAST_TTL);
        } catch (RuntimeException e) {
            log.warn("record probe failure failed providerId={}: {}", providerId, e.toString());
        }
    }

    /** 最近一次探测结论；无记录返回 null。 */
    public ProbeResult last(String providerId) {
        StringRedisTemplate t = t();
        if (t == null || blank(providerId)) {
            return null;
        }
        try {
            Map<Object, Object> m = t.opsForHash().entries(lastKey(providerId));
            if (m == null || m.isEmpty()) {
                return null;
            }
            boolean ok = "1".equals(str(m, "ok"));
            long ts = parseLong(m, "ts");
            String msg = str(m, "message");
            return new ProbeResult(ok, ts, msg);
        } catch (RuntimeException e) {
            log.warn("read probe result failed providerId={}: {}", providerId, e.toString());
            return null;
        }
    }

    static String failKey(String providerId) {
        return "mh:probe:fail:" + providerId;
    }

    static String lastKey(String providerId) {
        return "mh:probe:last:" + providerId;
    }

    private static Map<String, String> last(int ok, long epochMs, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ok", String.valueOf(ok));
        m.put("ts", String.valueOf(epochMs));
        m.put("message", message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message));
        return m;
    }

    private StringRedisTemplate t() {
        return redis == null ? null : redis.getIfAvailable();
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

    /** 最近一次探测结论。 */
    public record ProbeResult(boolean ok, long epochMs, String message) {
    }
}

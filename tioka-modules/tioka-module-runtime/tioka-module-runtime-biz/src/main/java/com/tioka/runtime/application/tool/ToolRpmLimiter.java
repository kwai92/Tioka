package com.tioka.runtime.application.tool;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具 RPM 限流（CON-01 第 5 步；GOV-03 落地前用本类内存上限 + Redis 分钟桶）。
 *
 * <p>桶：{@code tool:{name}} 与 {@code workspace:{ws}}；任一超限即拒绝。
 * 未配置上限 = 不限。
 */
@Component
public class ToolRpmLimiter {
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;
    /** scopeType → (scopeId → rpm)；null 值表示删除上限。 */
    private final Map<String, Map<String, Integer>> limits = new ConcurrentHashMap<>();

    public ToolRpmLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 设置上限；{@code rpm == null} 删除（不限）。
     *
     * @param scopeType {@code tool} 或 {@code workspace}
     */
    public void setLimit(String scopeType, String scopeId, Integer rpm) {
        if (scopeType == null || scopeId == null) {
            return;
        }
        Map<String, Integer> byId = limits.computeIfAbsent(scopeType, k -> new ConcurrentHashMap<>());
        if (rpm == null) {
            byId.remove(scopeId);
        } else {
            byId.put(scopeId, Math.max(0, rpm));
        }
    }

    public Integer limitOf(String scopeType, String scopeId) {
        Map<String, Integer> byId = limits.get(scopeType);
        return byId == null ? null : byId.get(scopeId);
    }

    /** @return true 放行；false 超限 */
    public boolean tryAcquire(String toolName, String workspaceId) {
        Integer toolLim = limitOf("tool", toolName);
        Integer wsLim = limitOf("workspace", workspaceId);
        if (toolLim == null && wsLim == null) {
            return true;
        }
        String minute = MINUTE.format(Instant.now());
        if (toolLim != null && !incrOk("tool_rpm:tool:" + toolName + ":" + minute, toolLim)) {
            return false;
        }
        if (wsLim != null && !incrOk("tool_rpm:ws:" + workspaceId + ":" + minute, wsLim)) {
            return false;
        }
        return true;
    }

    private boolean incrOk(String key, int limit) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, Duration.ofMinutes(2));
        }
        return n != null && n <= limit;
    }
}

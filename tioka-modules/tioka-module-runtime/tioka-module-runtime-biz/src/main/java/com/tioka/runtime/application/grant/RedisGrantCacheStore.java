package com.tioka.runtime.application.grant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 实现：{@code rt:grant:{id}} JSON，TTL 60 秒（ADR-039）。
 * 失效广播经 Redis pub/sub 频道 {@code rt:grant:revoked}（事件载荷 = 授予 ID 列表 JSON）。
 */
@Component
public class RedisGrantCacheStore implements GrantCacheStore {
    public static final String KEY_PREFIX = "rt:grant:";
    public static final String REVOKED_CHANNEL = "rt:grant:revoked";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public RedisGrantCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String grantId) {
        return redis.opsForValue().get(KEY_PREFIX + grantId);
    }

    @Override
    public void put(String grantId, String bodyJson) {
        redis.opsForValue().set(KEY_PREFIX + grantId, bodyJson, TTL);
    }

    @Override
    public void evict(List<String> grantIds) {
        if (grantIds == null || grantIds.isEmpty()) {
            return;
        }
        redis.delete(grantIds.stream().map(id -> KEY_PREFIX + id).toList());
    }

    @Override
    public void publishRevoked(List<String> grantIds) {
        if (grantIds == null || grantIds.isEmpty()) {
            return;
        }
        try {
            redis.convertAndSend(REVOKED_CHANNEL, String.join(",", grantIds));
        } catch (RuntimeException ignored) {
            // 广播失败不影响主流程：TTL 60 秒兜底
        }
    }
}

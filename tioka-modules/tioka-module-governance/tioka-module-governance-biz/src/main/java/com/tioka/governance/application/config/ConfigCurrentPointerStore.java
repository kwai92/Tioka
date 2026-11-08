package com.tioka.governance.application.config;

import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * 跨实例共享的当前版本指针（Redis）。类型表指针由 {@code CfgStore.updateTypeTablePointer} 维护。
 */
public class ConfigCurrentPointerStore {

    public static final String REDIS_KEY_PREFIX = "cfg:current:";

    private final StringRedisTemplate redis;

    public ConfigCurrentPointerStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void set(ObjectRef ref, int versionNo, String versionId) {
        redis.opsForValue().set(redisKey(ref), versionNo + "|" + versionId);
    }

    public Optional<Pointer> get(ObjectRef ref) {
        String raw = redis.opsForValue().get(redisKey(ref));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        int bar = raw.indexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        try {
            int versionNo = Integer.parseInt(raw.substring(0, bar));
            String versionId = raw.substring(bar + 1);
            if (versionId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Pointer(versionNo, versionId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String redisKey(ObjectRef ref) {
        return REDIS_KEY_PREFIX + ref.objectType() + ":" + ref.objectId();
    }

    public record Pointer(int versionNo, String versionId) {}
}

package com.tioka.ontology.infrastructure;

import com.tioka.framework.id.Ids;
import com.tioka.ontology.application.fold.FoldLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Redis 折叠锁（ONT-07）：key {@code ont:fold:{storeKey}}，租约 60 秒（覆盖 PureFunctionExecutor
 * 30s 钩子超时；最坏锁过期自动失效 + input_hash 幂等兜底——决策已冻结）；不续约。
 * 照 runtime RedisThreadLock 模板：setIfAbsent tryAcquire + compare-and-DEL release。
 */
public class RedisFoldLock implements FoldLock {

    public static final String KEY_PREFIX = "ont:fold:";
    public static final Duration LEASE_TTL = Duration.ofSeconds(60);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisFoldLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Handle> tryAcquire(String storeKey) {
        String key = KEY_PREFIX + storeKey;
        String value = Ids.newId("fold");
        Boolean ok = redis.opsForValue().setIfAbsent(key, value, LEASE_TTL);
        if (!Boolean.TRUE.equals(ok)) {
            return Optional.empty();
        }
        return Optional.of(() -> redis.execute(RELEASE, Collections.singletonList(key), value));
    }

    /** 测试辅助：预占锁（模拟他人持有）。 */
    public void forceSet(String storeKey, Duration ttl) {
        redis.opsForValue().set(KEY_PREFIX + storeKey, "other", ttl);
    }

    static List<String> keys(String storeKey) {
        return List.of(KEY_PREFIX + storeKey);
    }
}

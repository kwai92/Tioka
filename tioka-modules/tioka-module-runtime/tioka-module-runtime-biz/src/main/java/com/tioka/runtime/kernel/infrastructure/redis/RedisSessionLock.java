package com.tioka.runtime.kernel.infrastructure.redis;

import com.tioka.runtime.config.KernelProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class RedisSessionLock implements SessionLock {
    private static final String KEY_PREFIX = "rt:lock:";

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final KernelProperties props;

    public RedisSessionLock(StringRedisTemplate redis, KernelProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Optional<Handle> tryAcquire(String threadId, String turnId) {
        String key = KEY_PREFIX + threadId;
        String value = props.getInstanceId() + ":" + turnId;
        Duration ttl = props.getLeaseTtl();
        Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
        if (!Boolean.TRUE.equals(ok)) {
            return Optional.empty();
        }
        return Optional.of(new RedisHandle(key, value, ttl));
    }

    private final class RedisHandle implements Handle {
        private final String key;
        private final String value;
        private final Duration ttl;

        RedisHandle(String key, String value, Duration ttl) {
            this.key = key;
            this.value = value;
            this.ttl = ttl;
        }

        @Override
        public boolean renew() {
            Long r = redis.execute(RENEW, Collections.singletonList(key),
                    value, String.valueOf(ttl.toMillis()));
            return r != null && r > 0;
        }

        @Override
        public void close() {
            redis.execute(RELEASE, Collections.singletonList(key), value);
        }
    }

    /** 测试辅助：强制覆盖锁（模拟过期后被他人抢走）。 */
    public void forceSet(String threadId, String instanceId, String turnId, Duration ttl) {
        redis.opsForValue().set(KEY_PREFIX + threadId, instanceId + ":" + turnId,
                ttl.toMillis(), TimeUnit.MILLISECONDS);
    }
}

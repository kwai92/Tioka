package com.tioka.infra.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class RedisRelayLock implements RelayLock {

    static final String KEY = "mq:relay:lock";

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final MessagingProperties props;

    public RedisRelayLock(StringRedisTemplate redis, MessagingProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean tryAcquire(String token) {
        Boolean ok = redis.opsForValue().setIfAbsent(KEY, token, props.getRelay().getLockTtl());
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean renew(String token) {
        Long r = redis.execute(RENEW, Collections.singletonList(KEY),
                token, String.valueOf(props.getRelay().getLockTtl().toMillis()));
        return r != null && r > 0;
    }

    @Override
    public void release(String token) {
        redis.execute(RELEASE, Collections.singletonList(KEY), token);
    }
}

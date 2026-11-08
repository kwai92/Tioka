package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * MH-03：binding 级 RPM/TPM 令牌桶（Redis Lua）。拿不到令牌时按等待毫秒睡眠并重试，
 * 总等待超过 {@code rate-limit-max-wait} 抛 {@link ErrorCode#RATE_LIMITED}。
 */
@Component
public class ModelRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(ModelRateLimiter.class);

    /**
     * KEYS[1]=桶键 ARGV：capacity, refillPerSec, cost, nowMs
     * 返回：0=成功；&gt;0=需等待毫秒。
     * 桶状态用 Hash：tokens / ts。
     */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            if capacity == nil or capacity <= 0 or cost == nil or cost <= 0 then
              return 0
            end
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            if refill > 0 and now > ts then
              local elapsed = (now - ts) / 1000.0
              tokens = math.min(capacity, tokens + elapsed * refill)
              ts = now
            end
            if tokens >= cost then
              tokens = tokens - cost
              redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
              redis.call('PEXPIRE', key, 120000)
              return 0
            end
            local need = cost - tokens
            local waitMs = 0
            if refill > 0 then
              waitMs = math.ceil(need / refill * 1000.0)
            else
              waitMs = 1000
            end
            if waitMs < 1 then waitMs = 1 end
            redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
            redis.call('PEXPIRE', key, 120000)
            return waitMs
            """,
            Long.class);

    /**
     * TPM 补差：调用后按实际用量调整。ARGV：capacity, refillPerSec, delta, nowMs
     * delta &gt; 0 再扣；delta &lt; 0 退还 |delta|。
     */
    private static final DefaultRedisScript<Long> ADJUST = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local delta = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            if capacity == nil or capacity <= 0 or delta == nil or delta == 0 then
              return 0
            end
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            if refill > 0 and now > ts then
              local elapsed = (now - ts) / 1000.0
              tokens = math.min(capacity, tokens + elapsed * refill)
              ts = now
            end
            tokens = math.min(capacity, math.max(0, tokens - delta))
            redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
            redis.call('PEXPIRE', key, 120000)
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration maxWait;

    public ModelRateLimiter(StringRedisTemplate redis,
                            @Value("${tioka.model.rate-limit-max-wait:10s}") Duration maxWait) {
        this.redis = redis;
        this.maxWait = maxWait == null ? Duration.ofSeconds(10) : maxWait;
    }

    /**
     * 排队获取 RPM(1) + TPM(estTokens)。先 RPM 后 TPM；无上限则直接放行。
     *
     * @return 预扣的 TPM 估算值（供成功后补差）；无 TPM 限流时为 0
     */
    public long acquire(String bindingId, Integer rpmLimit, Integer tpmLimit, long estTokens) {
        if (blank(bindingId)) {
            return 0L;
        }
        boolean needRpm = rpmLimit != null && rpmLimit > 0;
        boolean needTpm = tpmLimit != null && tpmLimit > 0 && estTokens > 0;
        if (!needRpm && !needTpm) {
            return 0L;
        }
        long waited = 0L;
        if (needRpm) {
            waited = acquireOne(rpmKey(bindingId), rpmLimit, rpmLimit / 60.0, 1, waited);
        }
        if (needTpm) {
            waited = acquireOne(tpmKey(bindingId), tpmLimit, tpmLimit / 60.0, estTokens, waited);
            return estTokens;
        }
        return 0L;
    }

    private long acquireOne(String key, int capacity, double refillPerSec, long cost, long waitedSoFar) {
        long maxWaitMs = Math.max(0L, maxWait.toMillis());
        long waited = waitedSoFar;
        while (true) {
            long wait = tryAcquire(key, capacity, refillPerSec, cost);
            if (wait <= 0) {
                return waited;
            }
            if (waited + wait > maxWaitMs) {
                throw new BizException(ErrorCode.RATE_LIMITED, "模型 binding 限流排队超时");
            }
            sleepQuiet(wait);
            waited += wait;
        }
    }

    /** 调用后按实际 token 与预扣差额补差（实际更大再扣，更小则退还）。 */
    public void reconcileTpm(String bindingId, Integer tpmLimit, long reserved, long actualTokens) {
        if (blank(bindingId) || tpmLimit == null || tpmLimit <= 0 || reserved <= 0) {
            return;
        }
        long actual = Math.max(0L, actualTokens);
        long delta = actual - reserved;
        if (delta == 0) {
            return;
        }
        try {
            redis.execute(ADJUST, List.of(tpmKey(bindingId)),
                    String.valueOf(tpmLimit),
                    String.valueOf(tpmLimit / 60.0),
                    String.valueOf(delta),
                    String.valueOf(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("tpm reconcile failed bindingId={}: {}", bindingId, e.toString());
        }
    }

    /** 直接对桶执行一次 acquire（供 TokenBucketIT）。返回 0 或等待毫秒。 */
    public long tryAcquireOnce(String key, int capacity, double refillPerSec, long cost) {
        return tryAcquire(key, capacity, refillPerSec, cost);
    }

    private long tryAcquire(String key, int capacity, double refillPerSec, long cost) {
        try {
            Long wait = redis.execute(ACQUIRE, List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSec),
                    String.valueOf(cost),
                    String.valueOf(System.currentTimeMillis()));
            return wait == null ? 0L : Math.max(0L, wait);
        } catch (RuntimeException e) {
            log.warn("rate-limit lua failed key={}: {}", key, e.toString());
            return 0L;
        }
    }

    public static String rpmKey(String bindingId) {
        return "mh:rl:" + bindingId + ":rpm";
    }

    public static String tpmKey(String bindingId) {
        return "mh:rl:" + bindingId + ":tpm";
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(Math.max(1L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.RATE_LIMITED, "限流等待被中断");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

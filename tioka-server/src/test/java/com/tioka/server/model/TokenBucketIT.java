package com.tioka.server.model;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.model.application.gateway.ModelRateLimiter;
import com.tioka.server.gate.support.DualInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-03：RPM 令牌桶 Lua 正确性。见 M1-03 TokenBucketIT。
 */
@Tag("it")
class TokenBucketIT {

    @Test
    void concurrentFiftySmoothAfterSecond() throws Exception {
        try (DualInstance di = DualInstance.start("three-tools",
                Map.of("tioka.model.rate-limit-max-wait", "15s"), false)) {
            ModelRateLimiter limiter = di.a().getBean(ModelRateLimiter.class);
            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            String bindingId = Ids.newId("rl");
            String key = ModelRateLimiter.rpmKey(bindingId);
            redis.delete(key);

            int rpmLimit = 60;
            double refill = rpmLimit / 60.0; // 1 token/s

            // 先耗尽突发容量，模拟桶空
            for (int i = 0; i < rpmLimit; i++) {
                long w = limiter.tryAcquireOnce(key, rpmLimit, refill, 1);
                assertEquals(0L, w, "突发内应立即放行 i=" + i);
            }
            long needWait = limiter.tryAcquireOnce(key, rpmLimit, refill, 1);
            assertTrue(needWait >= 800 && needWait <= 1200,
                    "桶空后下一枚应约等 1s: " + needWait);

            // 并发 50：在 refill=1/s 下从第 2 秒起平滑放行（不等全部成功，观察前 2s 节奏）
            redis.delete(key);
            // 预置 tokens=0
            redis.opsForHash().put(key, "tokens", "0");
            redis.opsForHash().put(key, "ts", String.valueOf(System.currentTimeMillis()));

            int n = 50;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger acquiredInFirst2s = new AtomicInteger();
            AtomicInteger acquiredTotal = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // 单次 try：拿不到则按 wait 睡一轮再拿（模拟 acquire 短片段）
                    long deadline = t0 + TimeUnit.SECONDS.toNanos(12);
                    while (System.nanoTime() < deadline) {
                        long w = limiter.tryAcquireOnce(key, rpmLimit, refill, 1);
                        if (w <= 0) {
                            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                            acquiredTotal.incrementAndGet();
                            if (elapsedMs >= 1000) {
                                acquiredInFirst2s.incrementAndGet();
                            }
                            return null;
                        }
                        Thread.sleep(Math.min(w, 200));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
            pool.shutdownNow();

            // 第 2 秒起应已有令牌放出（≥1）；12s 内约放出 ~12 枚，不应一次爆发 50
            assertTrue(acquiredInFirst2s.get() >= 1,
                    "第 2 秒起应开始放行, got=" + acquiredInFirst2s.get());
            assertTrue(acquiredTotal.get() >= 8 && acquiredTotal.get() <= 20,
                    "12s 内平滑放行约 8〜20（refill=1/s），实际=" + acquiredTotal.get());
            assertTrue(acquiredTotal.get() < n,
                    "不得在桶空时突发放行全部 50");
        }
    }

    @Test
    void acquireQueuesThenRateLimited() {
        try (DualInstance di = DualInstance.start("three-tools",
                Map.of("tioka.model.rate-limit-max-wait", "200ms"), false)) {
            ModelRateLimiter limiter = di.a().getBean(ModelRateLimiter.class);
            StringRedisTemplate redis = di.a().getBean(StringRedisTemplate.class);
            String bindingId = "bnd-" + UUID.randomUUID();
            redis.delete(ModelRateLimiter.rpmKey(bindingId));

            // rpm=1：先拿走唯一令牌
            limiter.acquire(bindingId, 1, null, 1);
            BizException ex = assertThrows(BizException.class,
                    () -> limiter.acquire(bindingId, 1, null, 1));
            assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        }
    }
}

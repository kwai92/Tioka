package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.runtime.kernel.infrastructure.redis.SessionLock;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-01：验证锁按 thread 隔离，不串行全局阻塞。
 * （完整「模型桩 Turn」路径在 RT-03 TurnScheduler 落地后升级本用例。）
 */
@SpringBootTest
@Tag("it")
class ConcurrencyIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired SessionLock sessionLock;

    @Test
    void hundredThreadsDoNotBlock() throws Exception {
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(n);

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String threadId = Ids.newId("thr");
            String turnId = Ids.newId("turn");
            futures.add(pool.submit(() -> {
                start.await();
                try (SessionLock.Handle h = sessionLock.tryAcquire(threadId, turnId).orElseThrow()) {
                    acquired.incrementAndGet();
                    Thread.sleep(500); // 模拟模型桩延迟
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        pool.shutdownNow();

        assertEquals(n, acquired.get());
        // 64 并发两批 ≈ 1s；旧全局 synchronized 会串行约 50s
        assertTrue(elapsedMs < 3000, "elapsedMs=" + elapsedMs);
    }
}

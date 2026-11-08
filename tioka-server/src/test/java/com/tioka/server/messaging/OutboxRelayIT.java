package com.tioka.server.messaging;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.OutboxRelay;
import com.tioka.infra.messaging.RelayLock;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ORC-02：双实例中继互斥；同 msg_key 保序用例见 {@code @Tag("mq")}（本包 IT 默认 in-memory）。
 */
@SpringBootTest
@Tag("it")
class OutboxRelayIT {

    private static final String LOCK_KEY = "mq:relay:lock";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        // 关闭后台中继，避免与测试持锁互相抢占
        registry.add("tioka.messaging.relay-enabled", () -> "false");
    }

    @Autowired RelayLock relayLock;
    @Autowired StringRedisTemplate redis;
    @Autowired EventPublisher publisher;
    @Autowired PrincipalHolder principals;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxRelay relay;

    @Test
    void singleRelayAcrossInstances() throws Exception {
        redis.delete(LOCK_KEY);
        String tokenA = "instance-a-" + UUID.randomUUID();
        String tokenB = "instance-b-" + UUID.randomUUID();

        assertTrue(relayLock.tryAcquire(tokenA), "实例 A 应拿到锁");
        assertTrue(!relayLock.tryAcquire(tokenB), "实例 B 在 A 持锁时不得抢占");

        AtomicInteger sentByA = new AtomicInteger();
        AtomicInteger sentByB = new AtomicInteger();

        // 先清空同库残留未发送行（其他 IT 在 relay-enabled=false 下会堆积），再隔离本用例的 1 条
        while (relay.drainOnceForTest(tokenA, n -> {}) > 0) {
            // drain leftovers
        }

        // A 持锁期间发布一条，仅持锁中继可推进
        String marker = "relay-" + System.nanoTime();
        tx.executeWithoutResult(status -> principals.runAs(principal(), () ->
                publisher.publish(new DomainEvent(
                        "tioka.orch.run_created.v1",
                        "tioka-orch",
                        "key-" + marker,
                        Map.of("runId", "run_" + marker, "marker", marker),
                        0))));

        relay.drainOnceForTest(tokenA, n -> sentByA.incrementAndGet());
        relay.drainOnceForTest(tokenB, n -> sentByB.incrementAndGet());
        assertEquals(1, sentByA.get(), "持锁实例应发出消息");
        assertEquals(0, sentByB.get(), "未持锁实例不得发送");

        // 杀掉持有者：释放锁后 B 在 lock-ttl（15s）内接管
        relayLock.release(tokenA);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        boolean acquired = false;
        while (System.nanoTime() < deadline) {
            if (relayLock.tryAcquire(tokenB)) {
                acquired = true;
                break;
            }
            Thread.sleep(200);
        }
        if (!acquired) {
            fail("杀掉持有者后 15 秒内另一实例未接管");
        }
        relayLock.release(tokenB);
    }

    private static Principal principal() {
        return new Principal("7", "ws_relay", PrincipalType.USER,
                Set.of("workspace_member"), Set.of("*"), "password");
    }
}

package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.infrastructure.redis.RedisSessionLock;
import com.tioka.runtime.kernel.infrastructure.redis.SessionLock;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class SessionLockIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired SessionLock sessionLock;
    @Autowired RedisSessionLock redisSessionLock;
    @Autowired KernelProperties props;
    @Autowired StringRedisTemplate redis;

    @Test
    void mutualExclusion() {
        String threadId = Ids.newId("thr");
        String turnA = Ids.newId("turn");
        String turnB = Ids.newId("turn");

        Optional<SessionLock.Handle> a = sessionLock.tryAcquire(threadId, turnA);
        assertTrue(a.isPresent());
        assertTrue(sessionLock.tryAcquire(threadId, turnB).isEmpty());

        a.get().close();

        Optional<SessionLock.Handle> b = sessionLock.tryAcquire(threadId, turnB);
        assertTrue(b.isPresent());
        assertTrue(b.get().renew());
        b.get().close();
    }

    @Test
    void releaseDoesNotDeleteOthersLock() {
        String threadId = Ids.newId("thr");
        String turnOld = Ids.newId("turn");
        String turnNew = Ids.newId("turn");
        String key = "rt:lock:" + threadId;
        String newValue = props.getInstanceId() + ":" + turnNew;

        SessionLock.Handle old = sessionLock.tryAcquire(threadId, turnOld).orElseThrow();
        redisSessionLock.forceSet(threadId, props.getInstanceId(), turnNew, props.getLeaseTtl());

        assertFalse(old.renew());
        old.close(); // 值不等 → 不得 DEL 新锁

        assertEquals(newValue, redis.opsForValue().get(key));
        assertTrue(sessionLock.tryAcquire(threadId, Ids.newId("turn")).isEmpty());
    }
}

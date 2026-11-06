package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.StreamPump;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Tag("it")
class StreamPumpIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired StreamPump pump;
    @Autowired EventStream streams;

    @Test
    void slowClientDoesNotBlockOthers() throws Exception {
        String slowThread = Ids.newId("thr");
        String fastThread = Ids.newId("thr");
        assertTrue(pump.tryAcquireConnection());
        assertTrue(pump.tryAcquireConnection());

        CountDownLatch fastGot = new CountDownLatch(1);
        AtomicLong publishedAt = new AtomicLong();
        AtomicLong fastDelayMs = new AtomicLong(-1);

        SseEmitter slow = new SseEmitter(60_000L);
        pump.subscribe(slowThread, "0-0", null, slow);

        SseEmitter fast = new SseEmitter(60_000L) {
            @Override
            public synchronized void send(SseEventBuilder builder) throws java.io.IOException {
                super.send(builder);
                long pub = publishedAt.get();
                if (pub > 0 && fastDelayMs.get() < 0) {
                    fastDelayMs.set(System.currentTimeMillis() - pub);
                    fastGot.countDown();
                }
            }
        };
        pump.subscribe(fastThread, "0-0", null, fast);

        Thread.sleep(200);
        publishedAt.set(System.currentTimeMillis());
        streams.publish(fastThread, "1", RuntimeEventType.ITEM_COMPLETED.wire(),
                "{\"schemaVersion\":1,\"type\":\"item.completed\",\"threadId\":\"" + fastThread
                        + "\",\"ts\":\"2020-01-01T00:00:00Z\",\"turnId\":\"t\",\"item\":{\"itemId\":\"i\",\"seq\":1,\"itemType\":\"message\"}}");

        assertTrue(fastGot.await(3, TimeUnit.SECONDS), "快客户端应在 3s 内收到事件");
        // XREAD BLOCK 上限 1s；断言慢客户端未拖垮泵（远小于人为阻塞）
        assertTrue(fastDelayMs.get() >= 0 && fastDelayMs.get() < 1200,
                "快客户端延迟应 < 1200ms，实际=" + fastDelayMs.get());
        slow.complete();
        fast.complete();
    }
}

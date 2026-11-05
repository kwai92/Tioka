package com.tioka.orchestration.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

@Service
public class LaterTriggerService {
    private final OrchService orchService;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clockMs;
    /** 测试钩子：true 时 delay==0 同步执行 ingest。 */
    private volatile boolean syncImmediate;

    @Autowired
    public LaterTriggerService(OrchService orchService) {
        this(orchService, Executors.newSingleThreadScheduledExecutor(), System::currentTimeMillis);
    }

    /** 测试用：可注入 scheduler / clock。 */
    LaterTriggerService(OrchService orchService, ScheduledExecutorService scheduler, LongSupplier clockMs) {
        this.orchService = orchService;
        this.scheduler = scheduler;
        this.clockMs = clockMs;
    }

    /** 仅测试使用。 */
    void enableSyncImmediate(boolean enabled) {
        this.syncImmediate = enabled;
    }

    public String schedule(String eventType, long delayMs, Map<String, Object> payload) {
        String key = payload != null && payload.get("idempotencyKey") != null
                ? String.valueOf(payload.get("idempotencyKey"))
                : "later-" + clockMs.getAsLong();
        Map<String, Object> body = payload == null ? Map.of() : payload;
        long delay = Math.max(0, delayMs);
        if (syncImmediate && delay == 0) {
            orchService.ingestEvent(eventType, key, body);
            return key;
        }
        scheduler.schedule(() -> orchService.ingestEvent(eventType, key, body),
                delay, TimeUnit.MILLISECONDS);
        return key;
    }
}

package com.tioka.orchestration.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-04：later schedule 返回 key；syncImmediate 下 delay=0 同步 ingest。
 */
class LaterTriggerServiceTest {

    @Test
    void syncImmediateIngestsWithReturnedKey() {
        RecordingOrch orch = new RecordingOrch();
        LaterTriggerService later = new LaterTriggerService(orch, null, () -> 42L);
        later.enableSyncImmediate(true);
        String key = later.schedule("later.tick", 0, Map.of("foo", "bar"));
        assertEquals("later-42", key);
        assertEquals(1, orch.calls.size());
        assertEquals("later.tick", orch.calls.get(0).eventType);
        assertEquals(key, orch.calls.get(0).idem);
    }

    @Test
    void usesPayloadIdempotencyKey() {
        RecordingOrch orch = new RecordingOrch();
        LaterTriggerService later = new LaterTriggerService(orch, null, System::currentTimeMillis);
        later.enableSyncImmediate(true);
        String key = later.schedule("e", 0, Map.of("idempotencyKey", "idem-1"));
        assertEquals("idem-1", key);
        assertEquals("idem-1", orch.calls.get(0).idem);
    }

    /** 仅覆盖 schedule→ingestEvent 调用链，不走真实规则引擎。 */
    static final class RecordingOrch extends OrchService {
        final List<Call> calls = new ArrayList<>();

        RecordingOrch() {
            super(new OrchRuleIngestTest.FakeOrchRepository(),
                    new OrchRuleService(new OrchRuleIngestTest.FakeOrchRepository()));
        }

        @Override
        public synchronized String ingestEvent(String eventType, String idempotencyKey, Map<String, Object> payload) {
            calls.add(new Call(eventType, idempotencyKey));
            return "run_later";
        }

        record Call(String eventType, String idem) {}
    }
}

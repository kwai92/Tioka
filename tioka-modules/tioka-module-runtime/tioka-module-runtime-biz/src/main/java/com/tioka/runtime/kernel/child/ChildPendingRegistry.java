package com.tioka.runtime.kernel.child;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** jobId → (turnId, callId)，供闸门桩完成异步任务（RT-09）。 */
@Component
public class ChildPendingRegistry {
    private final Map<String, Entry> byJobId = new ConcurrentHashMap<>();

    public void register(String jobId, String turnId, String callId) {
        if (jobId == null || turnId == null || callId == null) {
            return;
        }
        byJobId.put(jobId, new Entry(turnId, callId));
    }

    public Optional<Entry> take(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byJobId.remove(jobId));
    }

    public Optional<Entry> findByCall(String turnId, String callId) {
        for (Map.Entry<String, Entry> e : byJobId.entrySet()) {
            Entry v = e.getValue();
            if (turnId.equals(v.turnId()) && callId.equals(v.callId())) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public record Entry(String turnId, String callId) {}
}

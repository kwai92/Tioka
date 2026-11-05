package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.AuditSink;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Primary
@Component
public class FileAuditSink implements AuditSink {
    private final CopyOnWriteArrayList<Map<String, Object>> recent = new CopyOnWriteArrayList<>();

    @Override
    public void emit(String action, String actorId, Map<String, Object> detail) {
        Map<String, Object> row = Map.of(
                "at", Instant.now().toString(),
                "action", action == null ? "" : action,
                "actorId", actorId == null ? "" : actorId,
                "detail", detail == null ? Map.of() : detail
        );
        recent.add(row);
        if (recent.size() > 500) {
            recent.remove(0);
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        int n = Math.max(1, Math.min(limit, recent.size()));
        if (recent.isEmpty()) {
            emit("bootstrap", "system", Map.of("note", "audit sink ready"));
        }
        List<Map<String, Object>> copy = new ArrayList<>(recent);
        return copy.subList(Math.max(0, copy.size() - n), copy.size());
    }
}

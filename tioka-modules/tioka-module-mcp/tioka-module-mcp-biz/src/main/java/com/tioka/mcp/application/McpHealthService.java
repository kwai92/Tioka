package com.tioka.mcp.application;

import com.tioka.mcp.domain.McpStore;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class McpHealthService {
    private final McpStore store;
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> open = new ConcurrentHashMap<>();

    public McpHealthService(McpStore store) {
        this.store = store;
    }

    public Health check(String serverId) {
        var opt = store.getServer(serverId);
        if (opt.isEmpty()) return new Health(serverId, false, "NOT_FOUND", 0);
        McpStore.McpServer s = opt.get();
        if (Boolean.TRUE.equals(open.get(serverId))) {
            return new Health(serverId, false, "CIRCUIT_OPEN", failures.getOrDefault(serverId, new AtomicInteger()).get());
        }
        boolean ok = s.baseUrl() != null && (s.baseUrl().startsWith("mock://") || s.baseUrl().startsWith("http"));
        if (!ok) {
            int f = failures.computeIfAbsent(serverId, k -> new AtomicInteger()).incrementAndGet();
            if (f >= 3) open.put(serverId, true);
        } else {
            failures.put(serverId, new AtomicInteger());
            open.put(serverId, false);
        }
        return new Health(serverId, ok, ok ? "UP" : "DOWN", failures.getOrDefault(serverId, new AtomicInteger()).get());
    }

    public record Health(String serverId, boolean up, String status, int failureCount) {}
}

package com.tioka.governance.application.policy;

import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GOV-07 策略缓存（M3-05 §1.5）：按空间缓存已解析策略集与版本号；
 * 收到 {@code tioka.gov.policy_changed.v1}（{@link PolicyChangedConsumer}）失效该空间，
 * 60 秒（tioka.policy.cache-ttl）到期兜底重建。
 */
public class PolicyCache {

    /** 缓存兜底到期（秒）。 */
    private final long ttlSeconds;
    private final Loader loader;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public interface Loader {
        /** 求值输入：系统级 + 当前空间 + charter:{agentVersionId} 的 ACTIVE 策略（投影为 ResolvedPolicy）。 */
        List<ResolvedPolicy> load(String workspaceId);

        /** 当前生效的策略集版本号（写入审计，可还原当时生效的策略）。 */
        int version(String workspaceId);
    }

    public PolicyCache(long ttlSeconds, Loader loader) {
        this.ttlSeconds = ttlSeconds <= 0 ? 60 : ttlSeconds;
        this.loader = loader;
    }

    public Entry get(String workspaceId) {
        Entry hit = cache.get(workspaceId);
        Instant now = Instant.now();
        if (hit != null && hit.expiresAt.isAfter(now)) {
            return hit;
        }
        Entry fresh = new Entry(loader.load(workspaceId), loader.version(workspaceId),
                now.plusSeconds(ttlSeconds));
        cache.put(workspaceId, fresh);
        return fresh;
    }

    /** 收到策略变更事件：失效该空间缓存（下次求值重建）。 */
    public void invalidate(String workspaceId) {
        if (workspaceId != null) {
            cache.remove(workspaceId);
        }
    }

    public record Entry(List<ResolvedPolicy> policies, int version, Instant expiresAt) {}
}

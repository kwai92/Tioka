package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * GOV-07 策略缓存与跨实例刷新离线测试（M3-05 §1.5）：同空间缓存命中、policy_changed
 * 事件（{@link PolicyChangedConsumer}）失效本实例缓存后重建、系统级版本随载入更新。
 * 跨实例 Redis 广播的端到端版本归 CI（IT 禁真实 Redis 惯例）。
 */
class PolicyRefreshIT {

    @Test
    void cacheHitAvoidsReloading() {
        AtomicInteger loads = new AtomicInteger();
        PolicyCache cache = new PolicyCache(60, loader(loads, 1, "p1"));
        cache.get("ws1");
        PolicyCache.Entry again = cache.get("ws1");
        assertEquals(1, loads.get(), "命中缓存不回源");
        assertEquals("p1", again.policies().get(0).policyId());
        assertEquals(1, again.version());
    }

    @Test
    void crossInstanceInvalidationTriggersReload() {
        AtomicInteger loads = new AtomicInteger();
        PolicyCache.Loader loader = loader(loads, 1, "p1");
        PolicyCache instanceA = new PolicyCache(60, loader);
        PolicyCache instanceB = new PolicyCache(60, loader);
        instanceA.get("ws1");
        instanceB.get("ws1");
        assertEquals(2, loads.get(), "两个实例各自加载一次");

        PolicyChangedConsumer consumerOfA = new PolicyChangedConsumer(instanceA);
        consumerOfA.routeLocally("ws1");

        PolicyCache.Entry fresh = instanceA.get("ws1");
        assertEquals(3, loads.get(), "A 实例失效后重建");
        assertEquals("p1", fresh.policies().get(0).policyId());
        assertEquals(1, fresh.version());
        // 实例 B 未收到事件：仍持有旧缓存（60 秒 TTL 兜底），不回源
        instanceB.get("ws1");
        assertEquals(3, loads.get());
    }

    @Test
    void invalidateOtherWorkspaceDoesNotAffectCachedEntry() {
        AtomicInteger loads = new AtomicInteger();
        PolicyCache cache = new PolicyCache(60, loader(loads, 1, "p1"));
        cache.get("ws1");
        new PolicyChangedConsumer(cache).routeLocally("ws2");
        cache.get("ws1");
        assertEquals(1, loads.get(), "其他空间失效不影响本空间缓存");
    }

    @Test
    void invalidateNullWorkspaceIsNoop() {
        AtomicInteger loads = new AtomicInteger();
        PolicyCache cache = new PolicyCache(60, loader(loads, 1, "p1"));
        cache.get("ws1");
        cache.invalidate(null);
        cache.get("ws1");
        assertEquals(1, loads.get());
    }

    @Test
    void zeroTtlFallsBackToDefault60s() {
        AtomicInteger loads = new AtomicInteger();
        PolicyCache cache = new PolicyCache(0, loader(loads, 1, "p1"));
        PolicyCache.Entry e = cache.get("ws1");
        assertSame(e, cache.get("ws1"), "非法 TTL 归一到默认 60 秒");
        assertEquals(1, loads.get());
    }

    /** Loader：每次 load 计数并返回递增版本（模拟策略集演进）。 */
    private static PolicyCache.Loader loader(AtomicInteger loads, int version, String policyId) {
        return new PolicyCache.Loader() {
            @Override
            public List<ResolvedPolicy> load(String workspaceId) {
                loads.incrementAndGet();
                List<ResolvedPolicy> out = new ArrayList<>();
                out.add(new ResolvedPolicy(policyId,
                        "@id(\"" + policyId + "\")\n@effect(\"DENY\")\nforbid(principal, action, resource);",
                        true, PolicyDecision.DENY, PolicyMode.ENFORCE));
                return out;
            }

            @Override
            public int version(String workspaceId) {
                return version;
            }
        };
    }
}

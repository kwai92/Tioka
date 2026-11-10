package com.tioka.governance.application.autonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.api.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRU-09 策略语义离线测试（M3-05 §7）：{@code AutonomyPolicyIT#toolPolicyOnlyTightens}、
 * {@code AutonomyPolicyIT#observationPeriod}。等级存取与事件写库用内存实现（离线优先惯例）；
 * 跨实例 / 落库验证归 CI 登记。
 */
class AutonomyPolicyIT {

    private final AutonomyGate gate = new DefaultAutonomyGate();

    private static AutonomyContext ctx(int level, String reversibility, boolean estimated,
                                       boolean observation, String toolPolicy) {
        return new AutonomyContext(PolicyDecision.ALLOW, "chat", level, reversibility, estimated,
                "WRITE", observation, toolPolicy, "agt_1", "av_1", "order.update", false, false);
    }

    @Test
    void toolPolicyOnlyTightens() {
        // auto 不能越过等级：L1 显式 auto 仍审批
        assertEquals(AutonomyDecision.REQUIRE_APPROVAL, gate.decide(ctx(1, "REVERSIBLE", false, false, "auto")));
        // approve / deny 照办：L4 也被收紧
        assertEquals(AutonomyDecision.REQUIRE_APPROVAL, gate.decide(ctx(4, "REVERSIBLE", false, false, "approve")));
        assertEquals(AutonomyDecision.DENIED, gate.decide(ctx(4, "REVERSIBLE", false, false, "deny")));
        // 无显式配置：等级判定不受影响（L2 可逆 WRITE 自动提交）
        assertEquals(AutonomyDecision.AUTO_COMMIT, gate.decide(ctx(2, "REVERSIBLE", false, false, null)));
    }

    @Test
    void observationPeriod() {
        // 观察期内 L3/L4 按 L2
        assertEquals(AutonomyDecision.AUTO_COMMIT, gate.decide(ctx(3, "REVERSIBLE", false, true, null)));
        assertEquals(AutonomyDecision.REQUIRE_APPROVAL, gate.decide(ctx(3, "IRREVERSIBLE", false, true, null)));
        assertEquals(AutonomyDecision.REQUIRE_APPROVAL, gate.decide(ctx(4, "IRREVERSIBLE", false, true, null)));
        // 观察期外 L3 可逆自动提交
        assertEquals(AutonomyDecision.AUTO_COMMIT, gate.decide(ctx(3, "REVERSIBLE", false, false, null)));
        // 观察期不影响 L1/L2
        assertEquals(AutonomyDecision.REQUIRE_APPROVAL, gate.decide(ctx(1, "REVERSIBLE", false, true, null)));
    }

    @Test
    void setLevelWritesEventAndNotifies() {
        InMemoryAutonomyStore store = new InMemoryAutonomyStore();
        AtomicReference<Map<String, Object>> published = new AtomicReference<>();
        AutonomyChangeNotifier notifier = new AutonomyChangeNotifier(published::set, true);
        DefaultAutonomyService service = new DefaultAutonomyService(store, notifier,
                new SamplingPolicy.Fixed(false), null, new ObjectMapper());

        // 初始无记录 = L1
        assertEquals(1, service.levelOf("agt_1", "order.update"));

        // 负责人手动调高到 L3：写事件（PROMOTE）+ 发事件
        service.setLevel("agt_1", "order.update", AutonomyLevel.L3, "转正", "manual", "owner_1");
        assertEquals(3, service.levelOf("agt_1", "order.update"));
        assertEquals(1, store.events.size());
        assertEquals("PROMOTE", store.events.get(0).getKind());
        assertEquals("L1", store.events.get(0).getFromLevel());
        assertEquals("L3", store.events.get(0).getToLevel());
        assertEquals("L3", published.get().get("newLevel"));

        // 事故：直接降到 L1（INCIDENT）
        service.markIncident("agt_1", "order.update", "act_1", "误删数据");
        assertEquals(1, service.levelOf("agt_1", "order.update"));
        assertEquals("INCIDENT", store.events.get(1).getKind());

        // 影子报告 M3 恒空
        assertEquals(0, service.shadowReport("agt_1").cases());
    }

    @Test
    void recordAutoCommitBumpsStatsAndSamplesAtL3() {
        InMemoryAutonomyStore store = new InMemoryAutonomyStore();
        DefaultAutonomyService service = new DefaultAutonomyService(store,
                new AutonomyChangeNotifier(p -> { }, true),
                new SamplingPolicy.Fixed(true), null, new ObjectMapper());

        // L2 自动提交：只计 stats，不抽检
        service.recordAutoCommit("agt_1", "av_1", "order.update", 2, "act_1", "ws_1");
        assertEquals(0, store.events.size());
        assertTrue(store.stats.values().stream().anyMatch(s -> s.getStatsJson().contains("\"n\":1")));

        // L3 自动提交 + 命中采样：生成 SAMPLING 抽检记录
        service.recordAutoCommit("agt_1", "av_1", "order.update", 3, "act_2", "ws_1");
        assertEquals(1, store.events.size());
        assertEquals("SAMPLING", store.events.get(0).getKind());
        assertTrue(store.events.get(0).getEvidenceJson().contains("act_2"));

        // n 累计
        service.recordAutoCommit("agt_1", "av_1", "order.update", 3, "act_3", "ws_1");
        assertEquals(2, store.events.size());
        assertTrue(store.stats.values().stream()
                .anyMatch(s -> s.getStatsJson().contains("\"n\":3")));
    }
}

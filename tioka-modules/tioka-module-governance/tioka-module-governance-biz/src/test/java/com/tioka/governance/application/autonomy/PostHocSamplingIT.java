package com.tioka.governance.application.autonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.infrastructure.persistence.TrustAutonomyEventDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRU-09 事后抽检（M3-05 §7 / ADR-034「事后抽检」）：L3/L4 自动提交按 10% 抽样生成
 * 「抽检」记录（{@code trust_autonomy_event.kind=SAMPLING}，不阻塞）。
 * 固定采样器离线测；审批台渲染（F27 注册表）与驳回消费（等同撤销请求）归 TRU-10 / 登记。
 */
class PostHocSamplingIT {

    @Test
    void tenPercentSamplingProducesReviewForL3AndL4() {
        InMemoryAutonomyStore store = new InMemoryAutonomyStore();
        // 10% 稳定哈希采样：1000 个动作 ≈ 100 命中（放宽到 5%〜15% 断言）
        DefaultAutonomyService service = new DefaultAutonomyService(store,
                new AutonomyChangeNotifier(p -> { }, true),
                new SamplingPolicy.Percentage(10), null, new ObjectMapper());

        for (int i = 0; i < 1000; i++) {
            service.recordAutoCommit("agt_1", "av_1", "order.update", 3, "act_" + i, "ws_1");
        }
        long sampled = store.events.stream()
                .filter(e -> "SAMPLING".equals(e.getKind()))
                .count();
        assertTrue(sampled >= 50 && sampled <= 150, "10% 抽样命中数应在 50〜150，实际 " + sampled);
    }

    @Test
    void fixedSamplerOnForL3OffForL2() {
        InMemoryAutonomyStore store = new InMemoryAutonomyStore();
        DefaultAutonomyService service = new DefaultAutonomyService(store,
                new AutonomyChangeNotifier(p -> { }, true),
                new SamplingPolicy.Fixed(true), null, new ObjectMapper());

        service.recordAutoCommit("agt_1", "av_1", "order.update", 2, "act_l2", "ws_1");
        service.recordAutoCommit("agt_1", "av_1", "order.update", 3, "act_l3", "ws_1");
        service.recordAutoCommit("agt_1", "av_1", "order.update", 4, "act_l4", "ws_1");

        List<String> sampledActions = store.events.stream()
                .filter(e -> "SAMPLING".equals(e.getKind()))
                .map(TrustAutonomyEventDO::getEvidenceJson)
                .collect(Collectors.toList());
        assertEquals(2, sampledActions.size(), "L2 不抽检，L3/L4 全抽");
        assertTrue(sampledActions.get(0).contains("act_l3"));
        assertTrue(sampledActions.get(1).contains("act_l4"));
    }

    @Test
    void percentageSamplerIsDeterministicPerAction() {
        SamplingPolicy.Percentage sampler = new SamplingPolicy.Percentage(10);
        // 同一动作多次判定结果一致（幂等，避免重复抽检）
        boolean first = sampler.isSampled("agt_1", "order.update", "act_42");
        for (int i = 0; i < 5; i++) {
            assertEquals(first, sampler.isSampled("agt_1", "order.update", "act_42"));
        }
    }
}

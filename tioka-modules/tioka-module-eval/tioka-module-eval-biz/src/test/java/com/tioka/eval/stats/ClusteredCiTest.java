package com.tioka.eval.stats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-18（EVO-07）验收：ClusteredStats 纯单测（ADR-068 第 6 节 / L213-219 ClusteredCiTest）。
 * 断言：组内相关场景聚类 SE &gt; 朴素 SE；Wilson 区间；配对差异与相关系数；
 * 非劣效三分支（PASS / BLOCK / INCONCLUSIVE）与样本不足（&lt; 10 材料单元）标记。
 */
class ClusteredCiTest {

    // ------------------------------------------------------------------
    // 聚类 SE > 朴素 SE（ADR-068 L216 核心断言）
    // ------------------------------------------------------------------

    @Test
    void clusteredSeExceedsNaiveSeUnderIntraUnitCorrelation() {
        // 10 单元 × 5 判定：半数单元全 1、半数全 0 → 组内完全相关（ICC ≈ 1）
        Map<String, List<Double>> units = new LinkedHashMap<>();
        for (int u = 1; u <= 10; u++) {
            double v = u <= 5 ? 1.0 : 0.0;
            List<Double> judgments = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                judgments.add(v);
            }
            units.put("unit-" + u, judgments);
        }
        ClusteredStats.ClusteredCi ci = ClusteredStats.clustered(units);

        assertEquals(0.5, ci.clusteredMean(), 1e-9);
        assertEquals(10, ci.unitCount());
        assertEquals(50, ci.judgmentCount());
        assertTrue(ci.sampleSufficient(), "10 材料单元 = 样本充足阈值");

        // 朴素 SE = sd(0/1 序列)/√50 ≈ 0.0714；ICC≈1 → deff = 1+(5−1)×1 = 5 → 聚类 SE ≈ 0.1597
        double naiveSe = 0.5 / Math.sqrt(50); // 二值 0/1 半半的总体 sd
        assertTrue(ci.clusterSe() > naiveSe * 1.5,
                "组内相关时聚类 SE 必须显著大于朴素 SE: cluster=" + ci.clusterSe()
                        + " naive=" + naiveSe);
        // ICC=1 → effectiveN = N/deff = 10（单元数而非判定数）
        assertEquals(10.0, ci.effectiveN(), 1e-6);
        // 区间 = mean ± 1.96 × clusterSe
        assertEquals(0.5 - ClusteredStats.Z_95 * ci.clusterSe(), ci.ci95Lower(), 1e-9);
        assertEquals(0.5 + ClusteredStats.Z_95 * ci.clusterSe(), ci.ci95Upper(), 1e-9);
    }

    @Test
    void noClusteringDegradesToNaiveSe() {
        // 每单元仅 1 条判定（n0=1 → deff=1）：聚类 SE 退化为朴素 SE
        Map<String, List<Double>> units = new LinkedHashMap<>();
        for (int u = 1; u <= 20; u++) {
            units.put("unit-" + u, List.of(u % 2 == 0 ? 1.0 : 0.0));
        }
        ClusteredStats.ClusteredCi ci = ClusteredStats.clustered(units);
        List<Double> all = new ArrayList<>();
        units.values().forEach(all::addAll);
        double naiveSe = sd(all) / Math.sqrt(all.size());
        assertEquals(naiveSe, ci.clusterSe(), 1e-12);
    }

    // ------------------------------------------------------------------
    // Wilson 区间（比例口径，ADR-068 L147）
    // ------------------------------------------------------------------

    @Test
    void wilsonIntervalKnownValues() {
        double[] wi = ClusteredStats.wilson(0.5, 50);
        // 经典值：p=0.5、n=50 → [0.3677, 0.6323]
        assertEquals(0.368, wi[0], 0.002, "Wilson 下界");
        assertEquals(0.632, wi[1], 0.002, "Wilson 上界");
        // 边界：n≤0 与 p=0/1 不越界
        assertEquals(0.0, ClusteredStats.wilson(0.5, 0)[0]);
        double[] zero = ClusteredStats.wilson(0.0, 30);
        assertEquals(0.0, zero[0], 1e-12);
        assertTrue(zero[1] > 0 && zero[1] < 0.2, "零命中时上界仍为正且小");
    }

    @Test
    void clusteredCiUsesEffectiveNForWilson() {
        // 组内相关（ICC=1 → deff=n0=5）时 Wilson 分母取有效样本量 10 而非判定数 50
        // （4 单元全 1、6 单元全 0：单元内零变异、单元间有变异 → 聚类结构可辨识）
        Map<String, List<Double>> units = new LinkedHashMap<>();
        for (int u = 1; u <= 10; u++) {
            double v = u <= 4 ? 1.0 : 0.0;
            units.put("unit-" + u, List.of(v, v, v, v, v));
        }
        ClusteredStats.ClusteredCi ci = ClusteredStats.clustered(units);
        assertEquals(0.4, ci.clusteredMean(), 1e-9);
        assertEquals(10.0, ci.effectiveN(), 1e-6, "ICC=1 → effectiveN = 50/5 = 10");
        double[] withEffectiveN = ClusteredStats.wilson(0.4, 10);
        assertEquals(withEffectiveN[0], ci.wilsonLower(), 1e-9);
        assertEquals(withEffectiveN[1], ci.wilsonUpper(), 1e-9);
        assertTrue(ci.wilsonUpper() < 1.0, "小有效样本量的 Wilson 上界必须显著小于 1");
    }

    // ------------------------------------------------------------------
    // 配对差异 + 相关系数（ADR-068 L148）
    // ------------------------------------------------------------------

    @Test
    void pairedDiffMeanSeAndCorrelation() {
        Map<String, List<Double>> a = new LinkedHashMap<>();
        Map<String, List<Double>> b = new LinkedHashMap<>();
        for (int u = 1; u <= 20; u++) {
            // A 单元均值 = u/40（递增）；B = A − 0.1（严格同步 → 相关 = 1，diff = +0.1）
            double ma = u / 40.0;
            a.put("unit-" + u, List.of(ma, ma));
            b.put("unit-" + u, List.of(ma - 0.1, ma - 0.1));
        }
        ClusteredStats.PairedDiff d = ClusteredStats.paired(a, b);
        assertEquals(0.1, d.meanDiff(), 1e-9);
        assertEquals(0.0, d.clusterSe(), 1e-9, "单元内无变异 → 差值恒定 → SE=0");
        assertEquals(1.0, d.correlation(), 1e-9, "同步变化的两个版本相关系数 = 1");
        assertTrue(d.ci95Lower() > 0.03 && d.ci95Upper() < 0.2);
        assertEquals(20, d.unitCount());
        assertFalse(d.sampleInsufficient());
    }

    @Test
    void pairedDiffIgnoresUnalignedUnits() {
        Map<String, List<Double>> a = new LinkedHashMap<>();
        Map<String, List<Double>> b = new LinkedHashMap<>();
        for (int u = 1; u <= 12; u++) {
            a.put("unit-" + u, List.of(0.8));
            b.put("unit-" + u, List.of(0.7));
        }
        a.put("only-a", List.of(1.0)); // 未对齐单元不参与配对
        ClusteredStats.PairedDiff d = ClusteredStats.paired(a, b);
        assertEquals(12, d.unitCount(), "仅两侧都有数据的单元参与对齐");
        assertEquals(0.1, d.meanDiff(), 1e-9);
    }

    // ------------------------------------------------------------------
    // 非劣效三分支（ADR-068 L153-165）+ 样本不足（L151）
    // ------------------------------------------------------------------

    @Test
    void nonInferiorityThreeBranches() {
        // PASS：下界 ≥ −0.03
        assertEquals("PASS", ClusteredStats.nonInferiorityVerdict(-0.03, 0.05, 0.03, false));
        assertEquals("PASS", ClusteredStats.nonInferiorityVerdict(0.0, 0.02, 0.03, false));
        // BLOCK：上界 < −0.03（确实变差）
        assertEquals("BLOCK", ClusteredStats.nonInferiorityVerdict(-0.10, -0.04, 0.03, false));
        // INCONCLUSIVE：区间跨过 −容差（转人工，不自动放行）
        assertEquals("INCONCLUSIVE", ClusteredStats.nonInferiorityVerdict(-0.05, 0.01, 0.03, false));
        // 边界恰好：上界 = −容差不满足 BLOCK（< 严格），跨过 → INCONCLUSIVE
        assertEquals("INCONCLUSIVE", ClusteredStats.nonInferiorityVerdict(-0.08, -0.03, 0.03, false));
    }

    @Test
    void insufficientUnitsForcesInconclusive() {
        // 样本 < 10 材料单元：即便区间整体在容差内也 INCONCLUSIVE + sampleInsufficient
        Map<String, List<Double>> a = new LinkedHashMap<>();
        Map<String, List<Double>> b = new LinkedHashMap<>();
        for (int u = 1; u <= 5; u++) {
            a.put("unit-" + u, List.of(0.9));
            b.put("unit-" + u, List.of(0.9));
        }
        ClusteredStats.PairedDiff d = ClusteredStats.paired(a, b);
        assertTrue(d.sampleInsufficient());
        assertEquals("INCONCLUSIVE", d.verdict());
        assertEquals(ClusteredStats.DEFAULT_TOLERANCE, d.tolerance(), 1e-9);
        assertEquals(0.0, d.meanDiff(), 1e-9);
    }

    @Test
    void pairedVerdictsEndToEnd() {
        // 端到端：20 单元、差值恒定 −0.1（A 比基线差 10pp，区间整体 < −3pp）→ BLOCK；
        // A 与基线完全一致（diff=0，下界 ≥ −容差）→ PASS。配对方向恒为 (A − 基线)。
        Map<String, List<Double>> baseline = new LinkedHashMap<>();
        Map<String, List<Double>> worse = new LinkedHashMap<>();
        Map<String, List<Double>> same = new LinkedHashMap<>();
        for (int u = 1; u <= 20; u++) {
            double mb = 0.5 + (u % 2 == 0 ? 0.1 : -0.1); // 基线单元间有变异（相关系数可估）
            baseline.put("unit-" + u, List.of(mb, mb));
            worse.put("unit-" + u, List.of(mb - 0.1, mb - 0.1));
            same.put("unit-" + u, List.of(mb, mb));
        }
        assertEquals("BLOCK", ClusteredStats.paired(worse, baseline).verdict(),
                "均匀变差 10 个百分点（区间整体 < −3pp）→ 阻止");
        assertEquals("PASS", ClusteredStats.paired(same, baseline).verdict(),
                "完全一致（diff=0，下界 ≥ −容差）→ 非劣效通过");
    }

    @Test
    void emptyAndSingleUnitEdgeCases() {
        ClusteredStats.ClusteredCi empty = ClusteredStats.clustered(Map.of());
        assertEquals(0, empty.unitCount());
        assertFalse(empty.sampleSufficient());

        Map<String, List<Double>> single = Map.of("u1", List.of(1.0, 0.0, 1.0));
        ClusteredStats.ClusteredCi one = ClusteredStats.clustered(single);
        assertEquals(1, one.unitCount());
        assertEquals(0.0, one.clusterSe(), 1e-12, "单单元无法估计单元间方差 → SE=0");
        assertFalse(one.sampleSufficient());

        ClusteredStats.PairedDiff none = ClusteredStats.paired(Map.of(), Map.of());
        assertTrue(none.sampleInsufficient());
        assertEquals("INCONCLUSIVE", none.verdict());
    }

    private static double sd(List<Double> values) {
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.size();
        double ss = 0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (values.size() - 1));
    }
}

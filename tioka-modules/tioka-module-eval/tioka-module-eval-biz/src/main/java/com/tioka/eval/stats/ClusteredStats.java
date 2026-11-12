package com.tioka.eval.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * C-18（EVO-07）聚类区间与配对差异统计（ADR-068 第 6 节，纯函数、可离线单测）。
 *
 * <p><b>口径</b>（ADR-068 L144-149 表格）：
 * <ul>
 *   <li>均值类：按材料单元聚类的标准误，均值 ± 1.96 × 聚类标准误。ADR-068 未规定
 *       聚类 SE 具体公式，本实现取与 6.3 节功效估算同源的「设计效应」近似：
 *       聚类 SE = naive SE × √deff，deff = 1 + (n₀−1)·ICC（ICC 用单因素 ANOVA
 *       ICC(1) 估计并截断到 [0,1]，n₀ 为不平衡校正的平均单元大小）。组内正相关
 *       （ICC &gt; 0）时聚类 SE &gt; 朴素 SE，与 ClusteredCiTest 断言一致；
 *       每单元一条判定（n₀=1）时退化为朴素 SE。</li>
 *   <li>比例类且分母小：Wilson 区间；分母为材料单元内聚类时，先按材料单元算
 *       有效样本量 effectiveN = N / deff 再算 Wilson（ADR-068 L147）。</li>
 *   <li>两版本对比：同一批材料单元上的配对差异——逐单元差值的均值与聚类标准误，
 *       同时报两者皮尔逊相关系数（ADR-068 L148）。</li>
 *   <li>样本 &lt; 10 个材料单元：标「样本不足」（sampleInsufficient），非劣效判定
 *       返回 INCONCLUSIVE，不做自动显著性放行（ADR-068 L151）。</li>
 * </ul>
 *
 * <p><b>非劣效门禁三分支</b>（ADR-068 L153-165，容差缺省 3 个百分点 = 0.03）：
 * 配对差异 95% 区间下界 ≥ −容差 → PASS（非劣效）；区间上界 &lt; −容差 → BLOCK（确实变差）；
 * 区间跨过 −容差 → INCONCLUSIVE（转人工审批，不自动放行）。
 */
public final class ClusteredStats {

    /** 95% 区间 z 值。 */
    public static final double Z_95 = 1.96;
    /** 非劣效容差缺省：3 个百分点（ADR-068 L164）。 */
    public static final double DEFAULT_TOLERANCE = 0.03;
    /** 「样本不足」阈值：材料单元数（ADR-068 L151）。 */
    public static final int MIN_UNITS = 10;

    private ClusteredStats() {}

    // ------------------------------------------------------------------
    // 单组聚类区间
    // ------------------------------------------------------------------

    /**
     * 按材料单元聚类的区间估计。
     *
     * @param unitScores unit_key → 该单元全部判定分值（如 pass=1/fail=0）；
     *                   空列表单元忽略；unit_key 语义不参与计算（调用方保证同键同单元）
     */
    public static ClusteredCi clustered(Map<String, List<Double>> unitScores) {
        List<List<Double>> units = new ArrayList<>();
        double total = 0;
        int n = 0;
        for (List<Double> scores : unitScores.values()) {
            if (scores == null || scores.isEmpty()) {
                continue;
            }
            units.add(scores);
            for (double s : scores) {
                total += s;
            }
            n += scores.size();
        }
        int k = units.size();
        if (n == 0 || k == 0) {
            return new ClusteredCi(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        double mean = total / n;

        double naiveSe = sd(allValues(units)) / Math.sqrt(n);
        double[] deffN0 = designEffect(units, n, k);
        double deff = deffN0[0];
        double clusterSe = naiveSe * Math.sqrt(deff);
        if (k < 2) {
            clusterSe = 0; // 单单元无法估计单元间方差（样本必然不足）
        }

        // Wilson（比例口径）：分母取聚类有效样本量（ADR-068 L147）
        double effectiveN = deff > 0 ? n / deff : n;
        double[] wilson = wilson(mean, effectiveN);

        return new ClusteredCi(mean, clusterSe,
                mean - Z_95 * clusterSe, mean + Z_95 * clusterSe,
                k, n, effectiveN, wilson[0], wilson[1]);
    }

    /**
     * 设计效应与平均单元大小：deff = 1 + (n₀−1)·ICC，ICC = 单因素 ANOVA ICC(1)
     * = (MSB − MSW) / (MSB + (n₀−1)·MSW)，负值截断为 0（单元间无额外相似性 →
     * 聚类 SE 退化为朴素 SE），上限截断 1。
     */
    private static double[] designEffect(List<List<Double>> units, int n, int k) {
        if (k < 2 || n <= k) {
            return new double[]{1.0, 1.0};
        }
        double grand = 0;
        for (List<Double> u : units) {
            for (double v : u) {
                grand += v;
            }
        }
        grand /= n;
        double ssb = 0;
        double ssw = 0;
        double sumMi2 = 0;
        for (List<Double> u : units) {
            int m = u.size();
            double unitMean = 0;
            for (double v : u) {
                unitMean += v;
            }
            unitMean /= m;
            ssb += m * (unitMean - grand) * (unitMean - grand);
            for (double v : u) {
                ssw += (v - unitMean) * (v - unitMean);
            }
            sumMi2 += (double) m * m;
        }
        double msb = ssb / (k - 1);
        double msw = ssw / (n - k);
        double n0 = (n - sumMi2 / n) / (k - 1);
        double denom = msb + (n0 - 1) * msw;
        double icc = denom <= 0 ? 0 : (msb - msw) / denom;
        icc = Math.max(0, Math.min(1, icc));
        return new double[]{1.0 + Math.max(0, n0 - 1) * icc, n0};
    }

    /** Wilson 比例区间（z=1.96）；n ≤ 0 返回 [0,0]。 */
    public static double[] wilson(double p, double n) {
        if (n <= 0) {
            return new double[]{0, 0};
        }
        double z2 = Z_95 * Z_95;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double half = (Z_95 / denom)
                * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    // ------------------------------------------------------------------
    // 配对差异 + 非劣效
    // ------------------------------------------------------------------

    /**
     * 两 run 同材料单元对齐的配对差异（ADR-068 L148）。
     *
     * @param baseA 基线 run：unit_key → 判定分值
     * @param baseB 对照 run：unit_key → 判定分值；仅两侧都有数据的单元参与对齐
     */
    public static PairedDiff paired(Map<String, List<Double>> baseA, Map<String, List<Double>> baseB) {
        Map<String, double[]> aligned = new TreeMap<>(); // unit → {meanA, meanB}
        for (Map.Entry<String, List<Double>> e : baseA.entrySet()) {
            List<Double> b = baseB.get(e.getKey());
            if (b == null || b.isEmpty() || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            aligned.put(e.getKey(), new double[]{mean(e.getValue()), mean(b)});
        }
        int k = aligned.size();
        if (k == 0) {
            return new PairedDiff(0, 0, 0, 0, 0, 0, 0, true,
                    "INCONCLUSIVE", DEFAULT_TOLERANCE);
        }
        List<Double> diffs = new ArrayList<>(k);
        List<Double> meansA = new ArrayList<>(k);
        List<Double> meansB = new ArrayList<>(k);
        aligned.forEach((u, mb) -> {
            diffs.add(mb[0] - mb[1]);
            meansA.add(mb[0]);
            meansB.add(mb[1]);
        });
        double meanDiff = mean(diffs);
        // 单元级差值已是聚类单位（每单元一条差值，deff=1）：SE = sd(diffs)/√K
        double se = k >= 2 ? sd(diffs) / Math.sqrt(k) : 0;
        double lower = meanDiff - Z_95 * se;
        double upper = meanDiff + Z_95 * se;
        boolean insufficient = k < MIN_UNITS;
        String verdict = nonInferiorityVerdict(lower, upper, DEFAULT_TOLERANCE, insufficient);
        return new PairedDiff(meanDiff, se, lower, upper, k,
                pearson(meansA, meansB), k, insufficient, verdict, DEFAULT_TOLERANCE);
    }

    /**
     * 非劣效三分支（ADR-068 L153-165）：下界 ≥ −容差 → PASS；
     * 上界 &lt; −容差 → BLOCK；区间跨过 −容差 → INCONCLUSIVE。
     * 单元数 &lt; 10 → 一律 INCONCLUSIVE + sampleInsufficient（L151 样本不足不判定）。
     */
    public static String nonInferiorityVerdict(double ciLower, double ciUpper,
                                               double tolerance, boolean sampleInsufficient) {
        if (sampleInsufficient) {
            return "INCONCLUSIVE";
        }
        if (ciLower >= -tolerance) {
            return "PASS";
        }
        if (ciUpper < -tolerance) {
            return "BLOCK";
        }
        return "INCONCLUSIVE";
    }

    /** 皮尔逊相关系数（两版本同单元均值对）；任一侧方差为 0 时返回 0。 */
    private static double pearson(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        if (n < 2) {
            return 0;
        }
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a.get(i);
            mb += b.get(i);
        }
        ma /= n;
        mb /= n;
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < n; i++) {
            double da = a.get(i) - ma;
            double db = b.get(i) - mb;
            sab += da * db;
            saa += da * da;
            sbb += db * db;
        }
        if (saa <= 0 || sbb <= 0) {
            return 0;
        }
        return sab / Math.sqrt(saa * sbb);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s / values.size();
    }

    private static List<Double> allValues(List<List<Double>> units) {
        List<Double> out = new ArrayList<>();
        for (List<Double> u : units) {
            out.addAll(u);
        }
        return out;
    }

    /** 样本标准差（ddof=1）；n &lt; 2 返回 0。 */
    private static double sd(List<Double> values) {
        int n = values.size();
        if (n < 2) {
            return 0;
        }
        double mean = mean(values);
        double ss = 0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (n - 1));
    }

    // ------------------------------------------------------------------
    // 结果视图
    // ------------------------------------------------------------------

    /**
     * 单组聚类区间结果。
     *
     * @param clusteredMean     总体均值（全判定）
     * @param clusterSe         聚类标准误（deff 近似，见类注释）
     * @param ci95Lower/Upper   mean ± 1.96 × clusterSe
     * @param unitCount         材料单元数
     * @param judgmentCount     判定总数
     * @param effectiveN        Wilson 用的聚类有效样本量 N/deff
     * @param wilsonLower/Upper 比例口径 Wilson 95% 区间
     */
    public record ClusteredCi(double clusteredMean, double clusterSe,
                              double ci95Lower, double ci95Upper,
                              int unitCount, int judgmentCount, double effectiveN,
                              double wilsonLower, double wilsonUpper) {

        /** 样本是否充足（≥ 10 材料单元，ADR-068 L151）。 */
        public boolean sampleSufficient() {
            return unitCount >= MIN_UNITS;
        }
    }

    /**
     * 配对差异结果。
     *
     * @param meanDiff           逐单元差值均值（A − B）
     * @param clusterSe          单元级差值的聚类标准误
     * @param ci95Lower/Upper    meanDiff ± 1.96 × clusterSe
     * @param unitCount          对齐的材料单元数
     * @param correlation        两版本单元均值皮尔逊相关系数
     * @param effectiveN         聚类有效样本量
     * @param sampleInsufficient 单元数 &lt; 10（ADR-068 L151）
     * @param verdict            PASS / BLOCK / INCONCLUSIVE（ADR-068 L153-165）
     * @param tolerance          非劣效容差（缺省 0.03）
     */
    public record PairedDiff(double meanDiff, double clusterSe,
                             double ci95Lower, double ci95Upper,
                             int unitCount, double correlation, double effectiveN,
                             boolean sampleInsufficient, String verdict, double tolerance) {}
}

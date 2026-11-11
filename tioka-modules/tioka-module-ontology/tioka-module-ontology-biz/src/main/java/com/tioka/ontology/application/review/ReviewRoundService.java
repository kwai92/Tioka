package com.tioka.ontology.application.review;

import com.tioka.ontology.infrastructure.LeaseReviewRoundRepository;
import com.tioka.ontology.infrastructure.persistence.LeaseReviewRoundDO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSE-06 审查轮次服务（M3-04「LSE-06 Finding 与轮次」）。
 *
 * <p>局部重审判定：模型确认后（{@code contract.model.confirmed}），若 diff（人工更正
 * {@code diff_draft}）涉及的路径与上一轮任一 Finding 的 {@code modelRefs.path} 相交，
 * 或落在某个要点的 {@code requires} 路径上，则只对这些要点重跑，{@code reviewRound + 1}；
 * 未受影响的要点沿用上一轮结论（复制并改 {@code modelVersion}）。要点库发布新版本
 * 不自动重审（由法务手动发起）。
 *
 * <p>Tioka 侧只做幂等与重审判断（V175 {@code lease_review_round}）；结论本身
 * （lease.finding@1）只存业务系统。路径相交口径：精确相等，或一者为另一者的路径前缀
 * （{@code a} 与 {@code a.b}、{@code a[0]} 视为相交段）；索引级差异（{@code [0]} vs {@code [1]}）
 * 不视为相交。
 */
public class ReviewRoundService {

    private final LeaseReviewRoundRepository rounds;

    public ReviewRoundService(LeaseReviewRoundRepository rounds) {
        this.rounds = rounds;
    }

    /**
     * 局部重审：计算需重跑的要点码集合。
     *
     * @param diffPaths            diff（人工更正）涉及的模型路径
     * @param prevFindings         上一轮结论集合（lease.finding@1 结构：checkpointCode / modelRefs[].path）
     * @param requiresByCheckpoint 要点码 → requires 路径列表（来自领域包要点库配置）
     * @return 需重跑的 checkpointCode（未受影响要点沿用上一轮结论，不在其中）
     */
    public Set<String> affectedCheckpoints(List<String> diffPaths,
                                           List<Map<String, Object>> prevFindings,
                                           Map<String, List<String>> requiresByCheckpoint) {
        Set<String> affected = new LinkedHashSet<>();
        if (diffPaths == null || diffPaths.isEmpty()) {
            return affected;
        }
        if (prevFindings != null) {
            for (Map<String, Object> finding : prevFindings) {
                String code = str(finding.get("checkpointCode"));
                if (code == null) {
                    continue;
                }
                for (Object ref : list(finding.get("modelRefs"))) {
                    String path = str(objMap(ref).get("path"));
                    if (path != null && intersects(diffPaths, path)) {
                        affected.add(code);
                        break;
                    }
                }
            }
        }
        if (requiresByCheckpoint != null) {
            for (Map.Entry<String, List<String>> e : requiresByCheckpoint.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                for (String req : e.getValue()) {
                    if (req != null && intersects(diffPaths, req)) {
                        affected.add(e.getKey());
                        break;
                    }
                }
            }
        }
        return affected;
    }

    /** 下一轮号：该审查运行当前最大轮次 + 1（首轮 1）。 */
    public int nextRound(String reviewRunId) {
        return rounds.currentRound(reviewRunId) + 1;
    }

    /** 记录一轮（幂等 upsert，(review_run_id, round) 冲突覆盖）。 */
    public void recordRound(LeaseReviewRoundDO row) {
        rounds.upsert(row);
    }

    /** 路径相交：精确相等，或一者为另一者的前缀（分隔符 . 或 [ 处截断）。 */
    private static boolean intersects(List<String> diffPaths, String path) {
        for (String diff : diffPaths) {
            if (diff == null || diff.isBlank()) {
                continue;
            }
            if (diff.equals(path)
                    || diff.startsWith(path + ".") || diff.startsWith(path + "[")
                    || path.startsWith(diff + ".") || path.startsWith(diff + "[")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

package com.tioka.eval.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内置评估器：召回率 Recall@K（C-16 / KNW-07，设计出处
 * docs/architecture/design-notes/03-推进规划与详细设计-A至E.md L518「评测进 CI」）。
 *
 * <p>口径：expected.references 为期望命中的文档 ID 数组；actual.hits 为检索命中数组
 * （元素对象含 docId）。score = |topK 内命中 docId 集合 ∩ references| / |references|，
 * 全命中 passed。config 可含 {@code k}（缺省 5，取 hits 前 k 条参与计算）。
 * 空 references 或缺失 actual.hits → score 0 / passed=false。
 * 纯 JSON 逻辑、无外部依赖，离线可测；数据集级均值由执行器（KnowledgeRecallEvalService）计算。
 */
public class RecallAt5Evaluator implements Evaluator {

    public static final String TYPE = "recall_at_5";
    public static final int DEFAULT_K = 5;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        Set<String> references = new LinkedHashSet<>(EvalTexts.references(subject.expected()));
        if (references.isEmpty()) {
            return new EvalScore(0.0, false, "expected.references 为空，无法判定召回");
        }
        Set<String> hitDocIds = topKDocIds(subject.actual(), kOf(config));
        if (hitDocIds.isEmpty()) {
            return new EvalScore(0.0, false, "actual.hits 缺失或为空");
        }
        hitDocIds.retainAll(references);
        double score = (double) hitDocIds.size() / references.size();
        boolean passed = hitDocIds.containsAll(references);
        return new EvalScore(score, passed,
                passed ? "references 全部命中 top" + kOf(config)
                        : "top" + kOf(config) + " 内命中 " + hitDocIds.size() + "/" + references.size());
    }

    /** config.k（Number/数字字符串均可）；非法或缺省回退 5。 */
    static int kOf(Map<String, Object> config) {
        if (config == null) {
            return DEFAULT_K;
        }
        Object k = config.get("k");
        if (k instanceof Number n && n.intValue() >= 1) {
            return n.intValue();
        }
        if (k instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                return parsed >= 1 ? parsed : DEFAULT_K;
            } catch (NumberFormatException ignored) {
                // 回退缺省
            }
        }
        return DEFAULT_K;
    }

    /** actual.hits 数组前 k 个元素的 docId（去重保序）；结构缺失/非法返回空集。 */
    static Set<String> topKDocIds(JsonNode actual, int k) {
        Set<String> out = new HashSet<>();
        if (actual == null || actual.isNull()) {
            return out;
        }
        JsonNode hits = actual.get("hits");
        if (hits == null || !hits.isArray()) {
            return out;
        }
        int taken = 0;
        for (JsonNode hit : hits) {
            if (taken >= k) {
                break;
            }
            JsonNode docId = hit.get("docId");
            if (docId != null && !docId.isNull() && !docId.asText().isBlank()) {
                out.add(docId.asText());
                taken++;
            }
        }
        return out;
    }
}

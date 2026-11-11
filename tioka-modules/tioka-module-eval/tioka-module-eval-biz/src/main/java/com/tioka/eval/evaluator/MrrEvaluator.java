package com.tioka.eval.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内置评估器：MRR（平均倒数排名的单用例项，C-16 / KNW-07，设计出处
 * docs/architecture/design-notes/03-推进规划与详细设计-A至E.md L518「评测进 CI」）。
 *
 * <p>口径：expected.references 为期望命中的文档 ID 数组；actual.hits 为检索命中数组
 * （元素对象含 docId，按检索排序）。score = 1 / 首个命中 reference 的 hits 排名
 * （1-based；无命中 0，命中即 passed）。config 可含 {@code k}（缺省 5，只在前 k 条内找首个命中）。
 * 数据集级 MRR 均值由执行器（KnowledgeRecallEvalService）的 summary 计算。
 * 纯 JSON 逻辑、无外部依赖，离线可测。
 */
public class MrrEvaluator implements Evaluator {

    public static final String TYPE = "mrr";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        Set<String> references = new HashSet<>(EvalTexts.references(subject.expected()));
        if (references.isEmpty()) {
            return new EvalScore(0.0, false, "expected.references 为空，无法判定排名");
        }
        int k = RecallAt5Evaluator.kOf(config);
        JsonNode hits = subject.actual() == null ? null : subject.actual().get("hits");
        if (hits == null || !hits.isArray() || hits.isEmpty()) {
            return new EvalScore(0.0, false, "actual.hits 缺失或为空");
        }
        int rank = 0;
        int taken = 0;
        for (JsonNode hit : hits) {
            if (taken >= k) {
                break;
            }
            JsonNode docId = hit.get("docId");
            taken++;
            if (docId != null && !docId.isNull() && references.contains(docId.asText())) {
                rank = taken;
                break;
            }
        }
        if (rank == 0) {
            return new EvalScore(0.0, false, "top" + k + " 内无 reference 命中");
        }
        double score = 1.0 / rank;
        return new EvalScore(score, true, "首个命中排名 " + rank);
    }
}

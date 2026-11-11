package com.tioka.eval.evaluator;

import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内置评估器：引用命中（ADR-031）。expected.references 逐条须出现在 actual 中
 * （缺省回退单条 expected 文本）；score = 命中比例，全部命中才 passed。
 * 纯逻辑、无外部依赖，离线可测。
 */
public class CitationEvaluator implements Evaluator {

    public static final String TYPE = "citation";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        List<String> refs = EvalTexts.references(subject.expected());
        if (refs.isEmpty()) {
            return new EvalScore(0.0, false, "expected 无引用可判定");
        }
        String actual = EvalTexts.text(subject.actual());
        List<String> missing = new ArrayList<>();
        for (String ref : refs) {
            if (!actual.contains(ref)) {
                missing.add(ref);
            }
        }
        double score = (double) (refs.size() - missing.size()) / refs.size();
        boolean pass = missing.isEmpty();
        return new EvalScore(score, pass,
                pass ? "全部引用命中" : "引用未命中: " + String.join(", ", missing));
    }
}

package com.tioka.eval.evaluator;

import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;

import java.util.Map;
import java.util.Objects;

/**
 * 内置评估器：包含（ADR-031）。expected 文本出现在 actual 中得 1 分。
 * 纯逻辑、无外部依赖，离线可测。
 */
public class ContainsEvaluator implements Evaluator {

    public static final String TYPE = "contains";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        String expected = EvalTexts.text(subject.expected());
        String actual = EvalTexts.text(subject.actual());
        if (expected.isEmpty()) {
            return new EvalScore(0.0, false, "expected 为空，无法判定包含");
        }
        boolean pass = actual.contains(expected);
        return new EvalScore(pass ? 1.0 : 0.0, pass,
                pass ? "包含匹配通过" : "actual 未包含 expected");
    }
}

package com.tioka.eval.evaluator;

import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;

import java.util.Map;
import java.util.Objects;

/**
 * 内置评估器：精确匹配（ADR-031）。actual 与 expected 完全一致得 1 分。
 * 纯逻辑、无外部依赖，离线可测。
 */
public class ExactEvaluator implements Evaluator {

    public static final String TYPE = "exact";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EvalScore score(EvalSubject subject, Map<String, Object> config) {
        Objects.requireNonNull(subject, "subject");
        String expected = EvalTexts.text(subject.expected());
        String actual = EvalTexts.text(subject.actual());
        boolean pass = Objects.equals(expected, actual);
        return new EvalScore(pass ? 1.0 : 0.0, pass,
                pass ? "精确匹配通过" : "actual 与 expected 不一致");
    }
}

package com.tioka.eval.api.spi;

import java.util.Map;

/**
 * 评估器 SPI（ADR-031 / EVO-02）。实现由 eval-biz 或领域包注册。
 */
public interface Evaluator {

    /** 评估器类型编码，如 exact / contains / citation / llm_judge。 */
    String type();

    EvalScore score(EvalSubject subject, Map<String, Object> config);
}

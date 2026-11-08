package com.tioka.agent.api.spec;

/** 结构化产出策略（ADR-066 §4.2）。 */
public enum OutputStrategy {
    /** 一次调用直接产出。 */
    DIRECT,
    /** 先自由推理，再单独做格式转换。 */
    REASON_THEN_FORMAT
}

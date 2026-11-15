package com.tioka.ontology.api.schema;

/** 结构化产出结局（ADR-066 §1.1 / SCH-07）。 */
public enum DataItemOutcome {
    /** 权威 Schema 与全部语义规则通过。 */
    VALID,
    /** 通过校验，但部分字段取了兜底值，或领域钩子给出 WARN。 */
    VALID_WITH_UNKNOWN,
    /** 自动链路失败，已生成人工补录任务，等待人补全。 */
    HUMAN_PENDING,
    /** 自动链路失败，契约声明不转人工。 */
    FAILED,
    /** 旧口径：待修复（SCH-09 降级阶梯接入后废弃）。 */
    NEEDS_REPAIR
}

package com.tioka.agent.api.spec;

/** 结构化输出提交方式（ADR-037）。 */
public enum OutputMode {
    /** Turn 最终产出。 */
    FINAL,
    /** 经 emit_output 工具多次提交。 */
    TOOL
}

package com.tioka.eval.api.mq;

/**
 * 评测域 MQ 主题常量（与 P3-00a {@code docs/contracts/events} 对齐）。
 */
public final class EvalMqTopics {

    public static final String EVAL_RUN_FINISHED = "tioka.eval.run_finished.v1";
    public static final String EVAL_FEEDBACK_RECORDED = "tioka.eval.feedback_recorded.v1";

    private EvalMqTopics() {}
}

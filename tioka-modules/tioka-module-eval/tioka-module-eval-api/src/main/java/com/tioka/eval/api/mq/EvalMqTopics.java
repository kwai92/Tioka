package com.tioka.eval.api.mq;

/**
 * 评测域 MQ 主题常量（与 P3-00a {@code docs/contracts/events} 对齐）。
 */
public final class EvalMqTopics {

    public static final String TOPIC = "tioka-eval";

    public static final String EVAL_RUN_FINISHED = "tioka.eval.run_finished.v1";
    public static final String EVAL_FEEDBACK_RECORDED = "tioka.eval.feedback_recorded.v1";

    /**
     * EVO-01 预留：法务驳回负向反馈（M3-06 §4.1）。产生方 LSE-05 未开工，
     * 消费路径先注册（事件契约见 docs/contracts/events/tioka.lease.review.feedback.v1.json）。
     */
    public static final String LEASE_REVIEW_FEEDBACK = "tioka.lease.review.feedback.v1";

    private EvalMqTopics() {}
}

package com.tioka.orchestration.api.mq;

/**
 * MQ Topic/Tag 与跨域事件名常量（禁止在 biz 内硬编码字符串）。
 * 跨域事件名见 M2-01 ORC-02 / {@code docs/contracts/events}。
 */
public final class OrchestrationMqTopics {

    public static final String TOPIC = "tioka-orch";

    public static final String TAG_EVENT_RECEIVED = "event.received";
    public static final String TAG_RUN_CLAIMED = "run.claimed";
    public static final String TAG_RUN_COMPLETED = "run.completed";
    public static final String TAG_ATTENTION_OPENED = "attention.opened";
    public static final String TAG_ATTENTION_ANSWERED = "attention.answered";
    public static final String TAG_TRIGGER_FIRED = "trigger.fired";

    public static final String RUN_CREATED = "tioka.orch.run_created.v1";
    public static final String RUN_FINISHED = "tioka.orch.run_finished.v1";

    private OrchestrationMqTopics() {
    }
}

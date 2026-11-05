package com.tioka.orchestration.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class OrchestrationMqTopics {

    public static final String TOPIC = "tioka-orch";

    public static final String TAG_EVENT_RECEIVED = "event.received";
    public static final String TAG_RUN_CLAIMED = "run.claimed";
    public static final String TAG_RUN_COMPLETED = "run.completed";
    public static final String TAG_ATTENTION_OPENED = "attention.opened";
    public static final String TAG_ATTENTION_ANSWERED = "attention.answered";
    public static final String TAG_TRIGGER_FIRED = "trigger.fired";

    private OrchestrationMqTopics() {
    }
}

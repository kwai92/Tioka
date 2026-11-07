package com.tioka.runtime.api.mq;

/**
 * MQ Topic / 事件名常量（禁止在 biz 内硬编码字符串）。事件名见 M2-01 ORC-02。
 */
public final class RuntimeMqTopics {

    public static final String TOPIC = "tioka-runtime";

    public static final String TURN_FINISHED = "tioka.runtime.turn_finished.v1";
    public static final String TURN_WAITING = "tioka.runtime.turn_waiting.v1";
    public static final String APPROVAL_REQUESTED = "tioka.runtime.approval_requested.v1";
    public static final String APPROVAL_DECIDED = "tioka.runtime.approval_decided.v1";
    public static final String CHILD_FINISHED = "tioka.runtime.child_finished.v1";

    private RuntimeMqTopics() {
    }
}

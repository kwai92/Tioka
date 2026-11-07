package com.tioka.flow.api.mq;

/**
 * MQ Topic / 事件名常量（禁止在 biz 内硬编码字符串）。事件名见 M2-01 ORC-02。
 */
public final class FlowMqTopics {

    public static final String TOPIC = "tioka-flow";

    public static final String RUN_FINISHED = "tioka.flow.run_finished.v1";

    private FlowMqTopics() {
    }
}

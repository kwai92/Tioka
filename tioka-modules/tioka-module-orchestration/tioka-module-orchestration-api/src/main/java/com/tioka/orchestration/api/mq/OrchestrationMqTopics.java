package com.tioka.orchestration.api.mq;

/**
 * MQ Topic / 事件名常量（禁止在 biz 内硬编码字符串）。事件名见 M2-01 ORC-02。
 */
public final class OrchestrationMqTopics {

    public static final String TOPIC = "tioka-orch";

    public static final String RUN_CREATED = "tioka.orch.run_created.v1";
    public static final String RUN_FINISHED = "tioka.orch.run_finished.v1";

    private OrchestrationMqTopics() {
    }
}

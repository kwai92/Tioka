package com.tioka.orchestration.api.watcher;

/**
 * 编排 MQ 消费者名常量（ORC-03；不改 {@code OrchestrationMqTopics}，避开 K6）。
 */
public final class OrchWatcherConsumers {

    /** 消费 {@code tioka.runtime.turn_finished.v1} / {@code turn_waiting.v1}，回写 Run。 */
    public static final String TURN_WATCHER = "orch-turn-watcher";

    /** 消费 {@code tioka.orch.run_created.v1}，派工到 Runtime / Flow。 */
    public static final String DISPATCHER = "orch-dispatcher";

    private OrchWatcherConsumers() {
    }
}

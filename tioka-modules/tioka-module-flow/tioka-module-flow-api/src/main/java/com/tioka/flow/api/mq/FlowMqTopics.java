package com.tioka.flow.api.mq;

/**
 * MQ Topic/Tag 与跨域事件名常量（禁止在 biz 内硬编码字符串）。
 *
 * <p>{@link #TAG_PUBLICATION_CHANGED} 中的 publication = {@code FlowPublication}（47-GL §1.6）。
 * 跨域事件名见 M2-01 ORC-02 / {@code docs/contracts/events}。
 */
public final class FlowMqTopics {

    public static final String TOPIC = "tioka-flow";

    public static final String TAG_DRAFT_SAVED = "draft.saved";
    public static final String TAG_VERSION_PUBLISHED = "version.published";
    public static final String TAG_RUN_STARTED = "run.started";
    public static final String TAG_RUN_COMPLETED = "run.completed";
    /** FlowPublication 通道变更（≠ AgentVersion）。 */
    public static final String TAG_PUBLICATION_CHANGED = "publication.changed";

    public static final String RUN_FINISHED = "tioka.flow.run_finished.v1";

    private FlowMqTopics() {
    }
}

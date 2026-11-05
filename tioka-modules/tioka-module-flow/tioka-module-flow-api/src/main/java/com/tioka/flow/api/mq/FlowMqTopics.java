package com.tioka.flow.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class FlowMqTopics {

    public static final String TOPIC = "tioka-flow";

    public static final String TAG_DRAFT_SAVED = "draft.saved";
    public static final String TAG_VERSION_PUBLISHED = "version.published";
    public static final String TAG_RUN_STARTED = "run.started";
    public static final String TAG_RUN_COMPLETED = "run.completed";
    public static final String TAG_PUBLICATION_CHANGED = "publication.changed";

    private FlowMqTopics() {
    }
}

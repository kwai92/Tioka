package com.tioka.knowledge.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class KnowledgeMqTopics {

    public static final String TOPIC = "tioka-knowledge";

    public static final String TAG_DOCUMENT_INGESTED = "document.ingested";
    public static final String TAG_INDEX_UPDATED = "index.updated";
    public static final String TAG_SYNC_FINISHED = "sync.finished";
    public static final String TAG_EVAL_FINISHED = "eval.finished";

    private KnowledgeMqTopics() {
    }
}

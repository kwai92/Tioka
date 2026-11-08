package com.tioka.knowledge.api.mq;

/**
 * Knowledge / Eval MQ Topic/Tag 与跨域事件名（禁止在 biz 内硬编码字符串）。
 * <p>评测模块（P3-00b）落地前，评测事件常量暂挂本类；P3-00a 契约先行。
 */
public final class KnowledgeMqTopics {

    public static final String TOPIC = "tioka-knowledge";

    public static final String TAG_DOCUMENT_INGESTED = "document.ingested";
    public static final String TAG_INDEX_UPDATED = "index.updated";
    public static final String TAG_SYNC_FINISHED = "sync.finished";
    public static final String TAG_EVAL_FINISHED = "eval.finished";

    public static final String INGEST_STAGE = "tioka.knowledge.ingest_stage.v1";
    public static final String EXTERNAL_QUERIED = "tioka.knowledge.external_queried.v1";
    public static final String EVAL_RUN_FINISHED = "tioka.eval.run_finished.v1";
    public static final String EVAL_FEEDBACK_RECORDED = "tioka.eval.feedback_recorded.v1";

    private KnowledgeMqTopics() {
    }
}

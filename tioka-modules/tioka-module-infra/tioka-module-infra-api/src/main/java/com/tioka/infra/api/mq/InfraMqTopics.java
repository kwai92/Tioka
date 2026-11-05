package com.tioka.infra.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class InfraMqTopics {

    public static final String TOPIC = "tioka-infra";

    public static final String TAG_FILE_UPLOADED = "file.uploaded";
    public static final String TAG_JOB_FINISHED = "job.finished";
    public static final String TAG_CONFIG_CHANGED = "config.changed";

    private InfraMqTopics() {
    }
}

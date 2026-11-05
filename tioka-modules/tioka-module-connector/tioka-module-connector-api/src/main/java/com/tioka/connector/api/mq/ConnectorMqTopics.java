package com.tioka.connector.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class ConnectorMqTopics {

    public static final String TOPIC = "tioka-connector";

    public static final String TAG_IM_MESSAGE_SENT = "im.message.sent";
    public static final String TAG_IM_EVENT_RECEIVED = "im.event.received";
    public static final String TAG_SYNC_PUSHED = "sync.pushed";
    public static final String TAG_SYNC_PULLED = "sync.pulled";

    private ConnectorMqTopics() {
    }
}

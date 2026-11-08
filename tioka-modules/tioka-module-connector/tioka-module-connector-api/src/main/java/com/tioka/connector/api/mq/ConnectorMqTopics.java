package com.tioka.connector.api.mq;

/**
 * MQ Topic/Tag 与跨域事件名常量（禁止在 biz 内硬编码字符串）。
 * 跨域事件名见 M2-01 ORC-02 / {@code docs/contracts/events}。
 */
public final class ConnectorMqTopics {

    public static final String TOPIC = "tioka-connector";

    public static final String TAG_IM_MESSAGE_SENT = "im.message.sent";
    public static final String TAG_IM_EVENT_RECEIVED = "im.event.received";
    public static final String TAG_SYNC_PUSHED = "sync.pushed";
    public static final String TAG_SYNC_PULLED = "sync.pulled";

    public static final String IM_SEND_REQUESTED = "tioka.connector.im_send_requested.v1";
    public static final String IM_INBOUND = "tioka.connector.im_inbound.v1";
    /** NTF-01 通知投递请求（渠道消费者）。 */
    public static final String NTF_DELIVERY_REQUESTED = "tioka.ntf.delivery_requested.v1";

    private ConnectorMqTopics() {
    }
}

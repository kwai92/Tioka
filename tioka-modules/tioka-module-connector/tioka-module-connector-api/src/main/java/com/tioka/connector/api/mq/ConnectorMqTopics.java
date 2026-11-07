package com.tioka.connector.api.mq;

/**
 * MQ Topic / 事件名常量（禁止在 biz 内硬编码字符串）。事件名见 M2-01 ORC-02。
 */
public final class ConnectorMqTopics {

    public static final String TOPIC = "tioka-connector";

    public static final String IM_SEND_REQUESTED = "tioka.connector.im_send_requested.v1";
    public static final String IM_INBOUND = "tioka.connector.im_inbound.v1";

    private ConnectorMqTopics() {
    }
}

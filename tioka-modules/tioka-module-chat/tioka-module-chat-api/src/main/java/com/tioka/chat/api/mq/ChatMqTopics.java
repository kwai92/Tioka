package com.tioka.chat.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class ChatMqTopics {

    public static final String TOPIC = "tioka-chat";

    public static final String TAG_CONVERSATION_CREATED = "conversation.created";
    public static final String TAG_MESSAGE_APPENDED = "message.appended";
    public static final String TAG_MESSAGE_STREAMED = "message.streamed";

    private ChatMqTopics() {
    }
}

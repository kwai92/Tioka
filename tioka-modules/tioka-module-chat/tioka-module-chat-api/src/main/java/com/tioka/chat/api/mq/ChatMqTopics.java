package com.tioka.chat.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 *
 * <p>{@code message.*} 是 Chat <strong>产品</strong>事件，不等于 Runtime {@code item.*}
 *（见 47-GL § Chat / ADR-022）。
 */
public final class ChatMqTopics {

    public static final String TOPIC = "tioka-chat";

    public static final String TAG_CONVERSATION_CREATED = "conversation.created";
    /** 产品侧「消息追加」语义；载荷应对齐 Runtime Item，勿再写 chat_message。 */
    public static final String TAG_MESSAGE_APPENDED = "message.appended";
    public static final String TAG_MESSAGE_STREAMED = "message.streamed";

    private ChatMqTopics() {
    }
}

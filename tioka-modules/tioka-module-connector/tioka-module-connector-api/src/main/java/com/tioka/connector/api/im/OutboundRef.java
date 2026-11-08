package com.tioka.connector.api.im;

/** 出站消息引用，用于流式更新卡片。 */
public record OutboundRef(String channel, String messageId, String chatId) {}

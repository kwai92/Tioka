package com.tioka.connector.api.im;

import java.util.Map;

/**
 * 验签解密后的入站消息。
 *
 * @param dedupeKey        平台事件 ID，用于去重
 * @param externalChatId   群/会话 ID
 * @param externalThreadId 话题 ID，可空
 * @param externalUserId   发送人外部 ID
 * @param text             纯文本，可空
 * @param mentionedBot     是否 @机器人
 * @param attrs            额外属性
 */
public record InboundMessage(
        String dedupeKey,
        String externalChatId,
        String externalThreadId,
        String externalUserId,
        String text,
        boolean mentionedBot,
        Map<String, Object> attrs) {

    public InboundMessage {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }
}

package com.tioka.system.api.spi;

import java.util.Map;

/** IM 通道适配器（飞书/钉钉/企微）。 */
public interface ImChannelAdapter {
    String channelType();
    void sendText(String conversationId, String text);
    void sendCard(String conversationId, Map<String, Object> card);
}

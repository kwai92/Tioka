package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.ImChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class NoopImChannelAdapter implements ImChannelAdapter {
    @Override public String channelType() { return "noop"; }
    @Override public void sendText(String conversationId, String text) { }
    @Override public void sendCard(String conversationId, Map<String, Object> card) { }
}

package com.tioka.runtime.agui;

import java.util.HashSet;
import java.util.Set;

/**
 * 映射会话状态：记录已发出 START 的 messageId，用于 completed 时区分「仅 END」与「START+CONTENT+END」。
 */
public final class AgUiMappingState {
    private final Set<String> textStarted = new HashSet<>();
    private final Set<String> thinkingStarted = new HashSet<>();

    public boolean markTextStarted(String messageId) {
        return textStarted.add(messageId);
    }

    public boolean wasTextStarted(String messageId) {
        return textStarted.contains(messageId);
    }

    public boolean markThinkingStarted(String messageId) {
        return thinkingStarted.add(messageId);
    }

    public boolean wasThinkingStarted(String messageId) {
        return thinkingStarted.contains(messageId);
    }
}

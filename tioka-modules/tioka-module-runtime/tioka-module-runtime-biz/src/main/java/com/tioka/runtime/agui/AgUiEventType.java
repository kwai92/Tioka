package com.tioka.runtime.agui;

/**
 * AG-UI 事件 type 字面量（对齐 {@code @ag-ui/core} EventType；reasoning 按 M2-04 使用 THINKING_*）。
 */
public final class AgUiEventType {
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_FINISHED = "RUN_FINISHED";
    public static final String RUN_ERROR = "RUN_ERROR";
    public static final String TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
    public static final String TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
    public static final String TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
    public static final String THINKING_TEXT_MESSAGE_START = "THINKING_TEXT_MESSAGE_START";
    public static final String THINKING_TEXT_MESSAGE_CONTENT = "THINKING_TEXT_MESSAGE_CONTENT";
    public static final String THINKING_TEXT_MESSAGE_END = "THINKING_TEXT_MESSAGE_END";
    public static final String TOOL_CALL_START = "TOOL_CALL_START";
    public static final String TOOL_CALL_ARGS = "TOOL_CALL_ARGS";
    public static final String TOOL_CALL_END = "TOOL_CALL_END";
    public static final String TOOL_CALL_RESULT = "TOOL_CALL_RESULT";
    public static final String CUSTOM = "CUSTOM";

    private AgUiEventType() {}
}

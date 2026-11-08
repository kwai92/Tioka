package com.tioka.runtime.api.pipeline;

import com.tioka.runtime.api.spi.ToolGateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具管线上下文（P3-00b / K1b）。步骤间只经本对象传值，不互相调用。
 */
public final class ToolPipelineContext {

    private final ToolGateway.ToolCallContext callContext;
    private final String toolName;
    private final Map<String, Object> args;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public ToolPipelineContext(ToolGateway.ToolCallContext callContext, String toolName, Map<String, Object> args) {
        this.callContext = callContext;
        this.toolName = toolName;
        this.args = args == null ? Map.of() : Map.copyOf(args);
    }

    public ToolGateway.ToolCallContext callContext() {
        return callContext;
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, Object> args() {
        return args;
    }

    public void put(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = attributes.get(key);
        if (v == null) {
            return null;
        }
        if (!type.isInstance(v)) {
            throw new IllegalStateException("pipeline attr " + key + " expected " + type.getSimpleName());
        }
        return (T) v;
    }

    public Map<String, Object> attributesView() {
        return Map.copyOf(attributes);
    }
}

package com.tioka.flow.api.dsl;

/**
 * ADR-043 §3.4 端口类型。
 */
public enum PortType {
    TEXT,
    NUMBER,
    BOOLEAN,
    JSON,
    LIST,
    FILE,
    DOCUMENT,
    MESSAGES,
    CHUNKS,
    ANY;

    public static PortType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("port type blank");
        }
        String t = raw.trim().toLowerCase();
        if (t.startsWith("list<") && t.endsWith(">")) {
            return LIST;
        }
        return switch (t) {
            case "text" -> TEXT;
            case "number" -> NUMBER;
            case "boolean" -> BOOLEAN;
            case "json" -> JSON;
            case "file" -> FILE;
            case "document" -> DOCUMENT;
            case "messages" -> MESSAGES;
            case "chunks" -> CHUNKS;
            case "any" -> ANY;
            default -> throw new IllegalArgumentException("unknown port type: " + raw);
        };
    }

    /** list&lt;T&gt; 的元素类型字面量；非 list 返回 null。 */
    public static String listElement(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase();
        if (t.startsWith("list<") && t.endsWith(">")) {
            return t.substring(5, t.length() - 1).trim();
        }
        return null;
    }
}

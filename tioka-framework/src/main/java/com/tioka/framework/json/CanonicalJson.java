package com.tioka.framework.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 JSON Canonicalization + SHA-256（ADR-029 第 50 条；AST-01 快照哈希）。
 *
 * <p>不新建 {@link ObjectMapper}，由调用方注入 Spring Bean 的 mapper。
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    /** 规范化序列化（无空格、对象键字典序）。 */
    public static String canonicalize(ObjectMapper mapper, Object value) {
        try {
            JsonNode node = value instanceof JsonNode j ? j : mapper.valueToTree(value);
            return writeCanonical(normalize(node));
        } catch (Exception e) {
            throw new IllegalStateException("CanonicalJson 序列化失败", e);
        }
    }

    /** 规范化 JSON 的 SHA-256 十六进制（小写 64 位）。 */
    public static String sha256Hex(ObjectMapper mapper, Object value) {
        String canonical = canonicalize(mapper, value);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = ((ArrayNode) node).arrayNode();
            for (JsonNode child : node) {
                out.add(normalize(child));
            }
            return out;
        }
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            src.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            ObjectNode out = src.objectNode();
            for (String k : keys) {
                out.set(k, normalize(src.get(k)));
            }
            return out;
        }
        return node;
    }

    private static String writeCanonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue());
            return;
        }
        if (node.isNumber()) {
            sb.append(node.decimalValue().stripTrailingZeros().toPlainString());
            return;
        }
        if (node.isTextual()) {
            sb.append('"');
            escape(node.textValue(), sb);
            sb.append('"');
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) sb.append(',');
                first = false;
                write(child, sb);
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            boolean first = true;
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                escape(e.getKey(), sb);
                sb.append('"').append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        }
    }

    private static void escape(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}

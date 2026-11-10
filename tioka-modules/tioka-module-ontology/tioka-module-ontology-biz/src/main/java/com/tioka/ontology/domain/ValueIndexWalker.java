package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 值索引遍历（ont_value_index 重建，M3-03 §1 / V172）：
 * 遍历 model_json，产出 {@code (path, status, valueText)} 条目。纯逻辑，无 Spring 依赖。
 *
 * <p>叶子判定：ValueCell（带 status 字段的对象）或标量。路径选择器与
 * {@link ModelPathResolver} 同一约定（x-tioka-identity / type / 下标）。
 * topics 由调用方按路径目录（去选择器的结构键）匹配后写入。
 */
public final class ValueIndexWalker {

    /** 一条值索引：path（实例路径）、status（归一化）、valueText（叶子值文本）。 */
    public record IndexPath(String path, ModelPathResolver.ValueStatus status, String valueText) {
    }

    private ValueIndexWalker() {
    }

    public static List<IndexPath> walk(JsonNode model) {
        List<IndexPath> out = new ArrayList<>();
        walkObject("m", model, out);
        return out;
    }

    private static void walkObject(String path, JsonNode node, List<IndexPath> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (var it = node.fields(); it.hasNext(); ) {
            var field = it.next();
            String childPath = path + "." + field.getKey();
            walkNode(childPath, field.getValue(), out);
        }
    }

    private static void walkNode(String path, JsonNode node, List<IndexPath> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.add(new IndexPath(path, ModelPathResolver.ValueStatus.UNKNOWN, null));
            return;
        }
        if (node.isValueNode()) {
            out.add(new IndexPath(path, ModelPathResolver.ValueStatus.KNOWN, textOf(node)));
            return;
        }
        if (node.isArray()) {
            int i = 0;
            for (JsonNode element : node) {
                String childPath;
                String identity = identityOf(element);
                childPath = identity != null ? path + "[" + identity + "]" : path + "[" + i + "]";
                walkNode(childPath, element, out);
                i++;
            }
            return;
        }
        // 对象
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()) {
            ModelPathResolver.ValueStatus vs = ModelPathResolver.ValueStatus.of(status.asText());
            JsonNode value = node.get("value");
            out.add(new IndexPath(path, vs, value == null || value.isNull() ? null : textOf(value)));
            return;
        }
        walkObject(path, node, out);
    }

    private static String identityOf(JsonNode element) {
        if (!element.isObject()) {
            return null;
        }
        JsonNode id = element.get("x-tioka-identity");
        if (id != null && id.isTextual()) {
            return id.asText();
        }
        JsonNode type = element.get("type");
        if (type != null && type.isTextual()) {
            return type.asText();
        }
        return null;
    }

    private static String textOf(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        String s = value.toString();
        return s.length() > 256 ? s.substring(0, 256) : s;
    }
}

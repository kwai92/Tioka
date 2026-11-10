package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路径级差异（M3-03 §7 DiffService）：{@code [{path, op: ADD/REMOVE/CHANGE, from, to}]}。
 * 数组按 {@code x-tioka-identity} 对齐（身份来自元素 {@code x-tioka-identity} 属性或 {@code type} 字段），
 * 顺序变化不产生差异；无身份的数组按下标对齐。输出路径带具体选择器（如
 * {@code m.feeItems[RENT@S1].sched.segments[0].amount}）。纯逻辑，无 Spring 依赖。
 */
public final class ModelPathDiff {

    public enum Op { ADD, REMOVE, CHANGE }

    public record Entry(String path, Op op, JsonNode from, JsonNode to) {
    }

    private ModelPathDiff() {
    }

    /** 对比两份 model_json；pathPrefix 通常为 "m"。 */
    public static List<Entry> diff(JsonNode from, JsonNode to) {
        List<Entry> out = new ArrayList<>();
        walk("m", from, to, out);
        return out;
    }

    private static void walk(String path, JsonNode from, JsonNode to, List<Entry> out) {
        if (from == null && to == null) {
            return;
        }
        if (from == null) {
            out.add(new Entry(path, Op.ADD, null, to));
            return;
        }
        if (to == null) {
            out.add(new Entry(path, Op.REMOVE, from, null));
            return;
        }
        boolean fromArr = from.isArray();
        boolean toArr = to.isArray();
        if (fromArr && toArr) {
            diffArrays(path, from, to, out);
            return;
        }
        if (fromArr != toArr) {
            if (!canonicalEquals(from, to)) {
                out.add(new Entry(path, Op.CHANGE, from, to));
            }
            return;
        }
        if (from.isObject() && to.isObject()) {
            // ValueCell 叶子：整 cell 比较（value 或 status 变化都是 CHANGE）
            if (ModelPathResolver.isValueCell(from) && ModelPathResolver.isValueCell(to)) {
                if (!canonicalEquals(from, to)) {
                    out.add(new Entry(path, Op.CHANGE, from, to));
                }
                return;
            }
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            from.fieldNames().forEachRemaining(names::add);
            to.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                walk(path + "." + name, from.get(name), to.get(name), out);
            }
            return;
        }
        if (!canonicalEquals(from, to)) {
            out.add(new Entry(path, Op.CHANGE, from, to));
        }
    }

    private static void diffArrays(String path, JsonNode from, JsonNode to, List<Entry> out) {
        Map<String, JsonNode> fromById = indexByIdentity(from);
        Map<String, JsonNode> toById = indexByIdentity(to);
        boolean identityAligned = !fromById.isEmpty() || !toById.isEmpty();
        if (!identityAligned) {
            // 无身份：按下标对齐
            int max = Math.max(from.size(), to.size());
            for (int i = 0; i < max; i++) {
                JsonNode f = i < from.size() ? from.get(i) : null;
                JsonNode t = i < to.size() ? to.get(i) : null;
                walk(path + "[" + i + "]", f, t, out);
            }
            return;
        }
        // 身份对齐：并集按 from 顺序优先（顺序变化不算差异）
        List<String> keys = new ArrayList<>();
        for (String k : fromById.keySet()) {
            keys.add(k);
        }
        for (String k : toById.keySet()) {
            if (!fromById.containsKey(k)) {
                keys.add(k);
            }
        }
        for (String key : keys) {
            String selector = "*".equals(key) ? key : "[" + key + "]";
            walk(path + selector, fromById.get(key), toById.get(key), out);
        }
    }

    /** 数组元素索引：x-tioka-identity 属性或 type 字段；两者皆无返回空 map（回退下标对齐）。 */
    private static Map<String, JsonNode> indexByIdentity(JsonNode array) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode element : array) {
            if (!element.isObject()) {
                continue;
            }
            String identity = null;
            JsonNode idNode = element.get("x-tioka-identity");
            if (idNode != null && idNode.isTextual()) {
                identity = idNode.asText();
            } else {
                JsonNode type = element.get("type");
                if (type != null && type.isTextual()) {
                    identity = type.asText();
                }
            }
            if (identity != null && !identity.isBlank()) {
                out.put(identity, element);
            }
        }
        return out;
    }

    private static boolean canonicalEquals(JsonNode a, JsonNode b) {
        return Objects.equals(String.valueOf(a), String.valueOf(b))
                || a.toString().equals(b.toString());
    }
}

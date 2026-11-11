package com.tioka.eval.evaluator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * EVO-02：EvalSubject 字段 → 参与打分的文本口径（内置评估器与批次执行共用）。
 * expected / actual 均为 JsonNode：字符串直接取值；对象优先取 {@code text} 字段；
 * 其余结构以规范 JSON 序列化参与比较，保证离线确定性。
 */
public final class EvalTexts {

    private EvalTexts() {}

    public static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        JsonNode text = node.get("text");
        if (text != null && text.isTextual()) {
            return text.asText();
        }
        return node.toString();
    }

    /** 引用命中口径：expected.references 数组；缺省回退单条 text。 */
    public static java.util.List<String> references(JsonNode expected) {
        if (expected == null || expected.isNull()) {
            return java.util.List.of();
        }
        JsonNode refs = expected.get("references");
        if (refs != null && refs.isArray() && !refs.isEmpty()) {
            java.util.List<String> out = new java.util.ArrayList<>();
            refs.forEach(r -> out.add(r.isTextual() ? r.asText() : r.toString()));
            return out;
        }
        String single = text(expected);
        return single.isBlank() ? java.util.List.of() : java.util.List.of(single);
    }
}

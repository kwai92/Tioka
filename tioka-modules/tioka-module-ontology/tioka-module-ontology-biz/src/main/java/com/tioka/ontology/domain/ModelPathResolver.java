package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型路径解析（M3-03 §6 路径语法）：
 *
 * <pre>
 * path     := root ('.' seg)*
 * root     := 'm'                                    # 当前实例
 * seg      := name selector?
 * selector := '[' key ('@' key)* ']' | '[*]' | '[' int ']'
 * </pre>
 *
 * 实例 model_json 约定（本批登记于批次文档）：
 * <ul>
 *   <li>集合为数组，元素对象带 {@code x-tioka-identity} 属性（值即选择器串，如 {@code RENT@S1}）；
 *       陈述类集合元素以 {@code type} 字段作为身份。</li>
 *   <li>叶子取值为 ValueCell 形态对象：{@code {"value":…, "status":…, "sources":[…]}}。</li>
 * </ul>
 *
 * 纯逻辑，无 Spring 依赖（M3-03 §0 ArchUnit：com.tioka.ontology.domain.. 不依赖 Spring）。
 */
public final class ModelPathResolver {

    /** 路径段：名称 + 可选选择器。 */
    public record Seg(String name, String selector) {
        public boolean hasSelector() {
            return selector != null;
        }
    }

    /** 取值状态（归一化）。 */
    public enum ValueStatus {
        KNOWN, UNKNOWN, BLANK, NOT_APPLICABLE, REVIEW;

        /** ValueCell.status 字段值 → 归一化状态；未知字面量按 UNKNOWN 处理。 */
        public static ValueStatus of(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toLowerCase()) {
                case "explicit", "derived", "default", "inherited", "known" -> KNOWN;
                case "review", "needs_review" -> REVIEW;
                case "blank" -> BLANK;
                case "not_applicable", "na" -> NOT_APPLICABLE;
                default -> UNKNOWN;
            };
        }

        /** 值索引（ont_value_index.status，VARCHAR(12)）存储口径。 */
        public String stored() {
            return this == NOT_APPLICABLE ? "NA" : name();
        }
    }

    private ModelPathResolver() {
    }

    /** 解析路径；不合法（root 非 m / 空段）抛 IllegalArgumentException。 */
    public static List<Seg> parse(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 为空");
        }
        String[] rawSegs = path.split("\\.");
        List<Seg> segs = new ArrayList<>();
        for (int i = 0; i < rawSegs.length; i++) {
            String raw = rawSegs[i].trim();
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("路径含空段: " + path);
            }
            if (i == 0) {
                if (!"m".equals(raw)) {
                    throw new IllegalArgumentException("路径必须以 m 开头: " + path);
                }
                segs.add(new Seg("m", null));
                continue;
            }
            String name = raw;
            String selector = null;
            int lb = raw.indexOf('[');
            if (lb >= 0) {
                if (!raw.endsWith("]")) {
                    throw new IllegalArgumentException("选择器未闭合: " + path);
                }
                name = raw.substring(0, lb);
                selector = unquote(raw.substring(lb + 1, raw.length() - 1));
                if (selector.isBlank()) {
                    throw new IllegalArgumentException("选择器为空: " + path);
                }
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException("段名为空: " + path);
            }
            segs.add(new Seg(name, selector));
        }
        return segs;
    }

    /** 按路径取节点；不存在返回 null。 */
    public static JsonNode resolve(JsonNode model, String path) {
        List<Seg> segs = parse(path);
        JsonNode cur = model;
        for (int i = 1; i < segs.size(); i++) {
            cur = step(cur, segs.get(i));
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static JsonNode step(JsonNode node, Seg seg) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(seg.name());
        if (child == null) {
            return null;
        }
        if (!seg.hasSelector()) {
            return child;
        }
        String sel = seg.selector();
        if ("*".equals(sel)) {
            // [*] 聚合口径：返回数组本身（状态在调用方聚合）
            return child.isArray() ? child : null;
        }
        if (!child.isArray()) {
            return null;
        }
        // 数字下标
        if (sel.chars().allMatch(Character::isDigit)) {
            int idx = Integer.parseInt(sel);
            return idx < child.size() ? child.get(idx) : null;
        }
        // 身份选择器：元素 x-tioka-identity 属性，或 type 字段（陈述类）
        for (JsonNode element : child) {
            if (!element.isObject()) {
                continue;
            }
            JsonNode identity = element.get("x-tioka-identity");
            if (identity != null && identity.isTextual() && identity.asText().equals(sel)) {
                return element;
            }
            JsonNode type = element.get("type");
            if (type != null && type.isTextual() && type.asText().equals(sel)) {
                return element;
            }
        }
        return null;
    }

    /** 剥离选择器两侧引号（CEL 形态 ['RENT@S1'] 与目录形态 [RENT@S1] 等价）。 */
    private static String unquote(String selector) {
        String s = selector.trim();
        if (s.length() >= 2
                && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 路径取值状态。叶子（ValueCell：带 status 字段的对象或标量）取自身状态；
     * 对象 / 数组对后代聚合，优先级 UNKNOWN &gt; BLANK &gt; NOT_APPLICABLE &gt; KNOWN；
     * 子树内任一取值为「待复核」时返回 REVIEW（待复核按已知值参与计算，由调用方记 needsReview）。
     * 无任何可判定节点 → UNKNOWN。
     */
    public static ValueStatus statusOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return ValueStatus.UNKNOWN;
        }
        if (node.isValueNode()) {
            return ValueStatus.KNOWN;
        }
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()) {
            return ValueStatus.of(status.asText());
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return ValueStatus.UNKNOWN;
            }
            ValueStatus agg = ValueStatus.KNOWN;
            boolean sawReview = false;
            for (JsonNode element : node) {
                ValueStatus s = statusOf(element);
                if (s == ValueStatus.REVIEW) {
                    sawReview = true;
                    continue;
                }
                agg = worse(agg, s);
            }
            return sawReview && agg == ValueStatus.KNOWN ? ValueStatus.REVIEW : agg;
        }
        // 对象：叶子 ValueCell 聚合
        ValueStatus agg = null;
        boolean sawReview = false;
        for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            java.util.Map.Entry<String, JsonNode> field = it.next();
            ValueStatus s = statusOf(field.getValue());
            if (s == ValueStatus.REVIEW) {
                sawReview = true;
                continue;
            }
            agg = agg == null ? s : worse(agg, s);
        }
        if (agg == null) {
            return sawReview ? ValueStatus.REVIEW : ValueStatus.UNKNOWN;
        }
        return sawReview && agg == ValueStatus.KNOWN ? ValueStatus.REVIEW : agg;
    }

    private static ValueStatus worse(ValueStatus a, ValueStatus b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(ValueStatus s) {
        return switch (s) {
            case UNKNOWN -> 0;
            case BLANK -> 1;
            case NOT_APPLICABLE -> 2;
            case KNOWN -> 3;
            case REVIEW -> 3;
        };
    }

    /** ValueCell 叶子判定：带 status 字段的对象。 */
    public static boolean isValueCell(JsonNode node) {
        return node != null && node.isObject() && node.has("status");
    }

    /** ValueCell 的值字段；非 cell 返回节点本身。 */
    public static JsonNode cellValue(JsonNode node) {
        if (isValueCell(node)) {
            return node.get("value");
        }
        return node;
    }
}

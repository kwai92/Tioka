package com.tioka.ontology.domain.cel;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.ontology.domain.ModelPathResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@code model_json} 暴露为 CEL 变量 {@code m}（M3-03 §9 / ADR-036）。
 *
 * <ul>
 *   <li>已知值（明示 / 推导 / 默认 / 继承 / 待复核）：叶子暴露为普通值，可直接参与运算。</li>
 *   <li>未知 / 空白 / 不适用：叶子暴露为 {@code optional.none()}（CEL optional）——
 *       对其比较 / 运算在运行期报「No matching overload」，{@code RuleEvaluator} 按错误路径记缺失；
 *       {@code check} 结果为 none → 缺失（ADR-029 第 53 条三值语义）。</li>
 *   <li>集合（数组）暴露为 map：键取元素 {@code x-tioka-identity} / {@code type}，无身份用下标。</li>
 *   <li>整数统一归一为 {@code Long}（cel-java 0.9.1 探针实证：Integer 在运行期
 *       {@code >=} 分派失配，Long / Double 正常）。</li>
 * </ul>
 *
 * 纯逻辑，无 Spring 依赖。
 */
public final class ModelValueProvider {

    private ModelValueProvider() {
    }

    /** model_json → CEL 变量 m 的激活结构。 */
    public static Map<String, Object> toCelVariable(JsonNode model) {
        if (model == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("m", convert(model));
        return out;
    }

    /** 单独取 m 的结构（RuleEvaluator 组装 activation 时使用）。 */
    public static Object convert(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isValueNode()) {
            return scalarOf(node);
        }
        if (node.isArray()) {
            Map<String, Object> map = new LinkedHashMap<>();
            int i = 0;
            for (JsonNode element : node) {
                map.put(keyOf(element, i++), convert(element));
            }
            return map;
        }
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()) {
            ModelPathResolver.ValueStatus vs = ModelPathResolver.ValueStatus.of(status.asText());
            if (vs == ModelPathResolver.ValueStatus.KNOWN || vs == ModelPathResolver.ValueStatus.REVIEW) {
                JsonNode value = node.get("value");
                return value == null || value.isNull() ? Optional.empty() : scalarOf(value);
            }
            return Optional.empty();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (var it = node.fields(); it.hasNext(); ) {
            var field = it.next();
            map.put(field.getKey(), convert(field.getValue()));
        }
        return map;
    }

    private static String keyOf(JsonNode element, int index) {
        if (element.isObject()) {
            JsonNode id = element.get("x-tioka-identity");
            if (id != null && id.isTextual()) {
                return id.asText();
            }
            JsonNode type = element.get("type");
            if (type != null && type.isTextual()) {
                return type.asText();
            }
        }
        return String.valueOf(index);
    }

    /** 标量转换：整数 → Long（cel-java 0.9.1 运行期比较分派要求），其余原样。 */
    static Object scalarOf(JsonNode node) {
        if (node.isInt() || node.isLong() || node.isShort() || node.isBigInteger()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    /** 求值前把表达式侧数值归一（cfg 由调用方以 Map 传入，同样归一）。 */
    public static Map<String, Object> normalizeCfg(Map<String, Object> cfg) {
        if (cfg == null) {
            return Map.of();
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : cfg.entrySet()) {
            out.put(e.getKey(), normalizeNumber(e.getValue()));
        }
        return out;
    }

    static Object normalizeNumber(Object v) {
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Short s) {
            return s.longValue();
        }
        if (v instanceof java.math.BigInteger bi) {
            return bi.longValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        if (v instanceof java.math.BigDecimal bd) {
            return bd.doubleValue();
        }
        return v;
    }

    /** 由 model_json 解析 touched 路径的值与状态（供 RuleResult.touched）；解析失败按未知处理。 */
    public static Map<String, Object> touchedValue(JsonNode model, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode node = null;
        try {
            node = ModelPathResolver.resolve(model, path);
        } catch (RuntimeException e) {
            // 非法路径（如提取器噪声）按未知处理
        }
        if (node == null) {
            out.put("status", ModelPathResolver.ValueStatus.UNKNOWN.name());
            out.put("value", null);
            return out;
        }
        ModelPathResolver.ValueStatus status = ModelPathResolver.statusOf(node);
        out.put("status", status.name());
        JsonNode value = ModelPathResolver.cellValue(node);
        out.put("value", value == null ? null : value.isValueNode() ? unwrap(value) : value.toString());
        out.put("sources", sourcesOf(node));
        return out;
    }

    private static Object unwrap(JsonNode value) {
        if (value.isInt() || value.isLong() || value.isShort() || value.isBigInteger()) {
            return value.asLong();
        }
        if (value.isDouble() || value.isFloat() || value.isBigDecimal()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sourcesOf(JsonNode node) {
        JsonNode sources = node.isObject() ? node.get("sources") : null;
        if (sources == null || !sources.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : sources) {
            if (s.isObject()) {
                out.add((Map<String, Object>) ObjectKV.convert(s));
            }
        }
        return out;
    }

    /** 最小 JSON→Map 转换（避免依赖注入 mapper）。 */
    private static final class ObjectKV {
        static Object convert(JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isValueNode()) {
                return node.isTextual() ? node.asText() : node.isBoolean() ? node.asBoolean()
                        : node.isNumber() ? node.asLong() : node.toString();
            }
            if (node.isArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonNode e : node) {
                    list.add(convert(e));
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (var it = node.fields(); it.hasNext(); ) {
                var f = it.next();
                map.put(f.getKey(), convert(f.getValue()));
            }
            return map;
        }
    }
}

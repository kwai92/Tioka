package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 类型化岗位授权书（TRU-02 / ADR-032「授权书结构」+ 2026-11-05 修订 delegable）。
 * {@link AgentSpec#charter} 保持 JsonNode 兼容；发布与执行路径经 {@link #parse} 用类型化投影。
 * {@code escalation} / {@code kpis} M3 只存不用（ADR-032），保留原始 JSON。
 */
public record Charter(
        String role,
        String departmentId,
        String mission,
        String actingAs,
        String serviceAccountId,
        List<ActionClass> actionClasses,
        List<String> forbiddenTools,
        List<String> delegable,
        JsonNode escalation,
        JsonNode kpis,
        String reviewEvery) {

    public Charter {
        if (delegable == null) {
            delegable = List.of();
        }
    }

    /** 按工具名解析动作类别（toolPatterns glob 匹配；READ 未归类工具返回 null）。 */
    public ActionClass classify(String toolName) {
        if (toolName == null || actionClasses == null) {
            return null;
        }
        for (ActionClass ac : actionClasses) {
            for (String glob : ac.toolPatterns()) {
                if (glob != null && globToPattern(glob.trim()).matcher(toolName).matches()) {
                    return ac;
                }
            }
        }
        return null;
    }

    /**
     * 可下放动作类别代码（MAS-02：父授权书可下放类别）。
     * 顶层 {@code delegable} 列表非空时优先；缺省按 {@link ActionClass#delegable()} 标志派生（C-2b 兼容口径）。
     */
    public List<String> delegableClasses() {
        if (delegable != null && !delegable.isEmpty()) {
            return delegable;
        }
        List<String> out = new ArrayList<>();
        if (actionClasses != null) {
            for (ActionClass ac : actionClasses) {
                if (ac.delegable()) {
                    out.add(ac.code());
                }
            }
        }
        return out;
    }

    public record ActionClass(
            String code,
            String name,
            List<String> toolPatterns,
            Limits limits,
            boolean fourEyes,
            String ceiling,
            boolean delegable) {}

    public record Limits(
            Long perCallAmountMicros,
            Long dailyAmountMicros,
            Integer perRunRecords,
            Integer dailyRecords,
            Map<String, List<String>> scope) {}

    // ---------- 类型化解析（发布路径用类型化，JSON 兼容保留） ----------

    public static Charter parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("charter 必须是对象");
        }
        return new Charter(
                text(node, "role"),
                text(node, "departmentId"),
                text(node, "mission"),
                text(node, "actingAs"),
                text(node, "serviceAccountId"),
                parseClasses(node.get("actionClasses")),
                parseStrings(node.get("forbiddenTools")),
                parseStrings(node.get("delegable")),
                node.get("escalation"),
                node.get("kpis"),
                text(node, "reviewEvery"));
    }

    private static List<ActionClass> parseClasses(JsonNode classes) {
        List<ActionClass> out = new ArrayList<>();
        if (classes != null && classes.isArray()) {
            for (JsonNode ac : classes) {
                JsonNode limits = ac.get("limits");
                Map<String, List<String>> scope = parseScope(limits == null ? null : limits.get("scope"));
                out.add(new ActionClass(
                        text(ac, "code"),
                        text(ac, "name"),
                        parseStrings(ac.get("toolPatterns")),
                        new Limits(
                                longOf(limits, "perCallAmountMicros"),
                                longOf(limits, "dailyAmountMicros"),
                                intOf(limits, "perRunRecords"),
                                intOf(limits, "dailyRecords"),
                                scope),
                        boolOf(ac, "fourEyes"),
                        text(ac, "ceiling"),
                        boolOf(ac, "delegable")));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> parseScope(JsonNode scope) {
        if (scope == null || !scope.isObject() || scope.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        var it = scope.fields();
        while (it.hasNext()) {
            var e = it.next();
            out.put(e.getKey(), parseStrings(e.getValue()));
        }
        return java.util.Map.copyOf(out);
    }

    private static List<String> parseStrings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode v : array) {
                if (v != null && !v.isNull()) {
                    out.add(v.asText());
                }
            }
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long longOf(JsonNode limits, String field) {
        JsonNode v = limits == null ? null : limits.get(field);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }

    private static Integer intOf(JsonNode limits, String field) {
        JsonNode v = limits == null ? null : limits.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static boolean boolOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.asBoolean(false);
    }

    /** 与 CharterPublishCheck 相同的 glob 口径（* 通配，其余字面量）。 */
    static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}

package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.routing.RoutingMode;
import com.tioka.model.api.versioning.ModelHubSpecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MH-06（ADR-024）路由计划 spec JSON → {@link RoutingPlanSnapshot} 纯函数解析。
 * <p>向后兼容口径（批次文档 §三）：
 * <ul>
 *   <li>spec 含 {@code mode} 字段 → MH-06 新结构（mode/groups/categoryToGroup/difficultyToGroup/
 *       decision/escalation/preferPreviousInGroup）。</li>
 *   <li>无 {@code mode} → V1 平铺 spec（ModelHubSpecs.planSpec：type/primaryBindingId/
 *       fallbackBindingId/hardBindingId/fallbackChain）→ 等价 FIXED：groups.main =
 *       [hardBindingId||primaryBindingId] + fallbackChain；preferPreviousInGroup=true
 *       （ADR-024 修订：不得默认关闭）。执行侧对 legacy 快照回退既有解析路径，行为不变。</li>
 * </ul>
 * 解析失败回退 legacy FIXED（不阻断调用）。
 */
public final class RoutingPlanSpecParser {

    public static final int DEFAULT_DECIDE_TIMEOUT_MS = 1_500;

    private RoutingPlanSpecParser() {
    }

    public static RoutingPlanSnapshot parse(JsonNode spec) {
        if (spec == null || !spec.has("mode")) {
            return legacySnapshot(spec);
        }
        try {
            return parseModeSpec(spec);
        } catch (RuntimeException e) {
            return legacySnapshot(spec);
        }
    }

    private static RoutingPlanSnapshot parseModeSpec(JsonNode spec) {
        RoutingMode mode = RoutingMode.valueOf(spec.get("mode").asText("FIXED")
                .trim().toUpperCase(Locale.ROOT));
        Map<String, List<String>> groups = new LinkedHashMap<>();
        JsonNode gs = spec.get("groups");
        if (gs != null && gs.isObject()) {
            gs.fields().forEachRemaining(e -> {
                List<String> ids = new ArrayList<>();
                if (e.getValue().isArray()) {
                    e.getValue().forEach(n -> {
                        String id = n.asText(null);
                        if (id != null && !id.isBlank()) {
                            ids.add(id.trim());
                        }
                    });
                }
                if (!ids.isEmpty()) {
                    groups.put(e.getKey(), ids);
                }
            });
        }
        if (groups.isEmpty() && spec.hasNonNull("primaryBindingId")) {
            groups.put("main", List.of(spec.get("primaryBindingId").asText()));
        }
        Map<String, String> categoryToGroup = stringMap(spec.get("categoryToGroup"));
        Map<String, String> difficultyToGroup = stringMap(spec.get("difficultyToGroup"));

        RoutingPlanSnapshot.DecisionConfig decision = null;
        JsonNode ds = spec.get("decision");
        if (ds != null && ds.isObject()) {
            int timeout = ds.has("timeoutMs") ? ds.get("timeoutMs").asInt(DEFAULT_DECIDE_TIMEOUT_MS)
                    : DEFAULT_DECIDE_TIMEOUT_MS;
            decision = new RoutingPlanSnapshot.DecisionConfig(
                    text(ds, "deciderId"),
                    text(ds, "bindingId"),
                    timeout > 0 ? timeout : DEFAULT_DECIDE_TIMEOUT_MS,
                    text(ds, "fallbackGroup"));
        }

        RoutingPlanSnapshot.EscalationPolicy escalation = null;
        JsonNode es = spec.get("escalation");
        if (es != null && es.isObject()) {
            escalation = new RoutingPlanSnapshot.EscalationPolicy(text(es, "freeGroup"), text(es, "mainGroup"));
        }

        boolean preferPrevious = !spec.has("preferPreviousInGroup") || spec.get("preferPreviousInGroup").asBoolean(true);
        return new RoutingPlanSnapshot(false, mode, groups, categoryToGroup, difficultyToGroup,
                decision, escalation, preferPrevious);
    }

    /** V1 平铺 spec → 等价 FIXED（口径见类注释）；无 spec 时给出空 FIXED。 */
    static RoutingPlanSnapshot legacySnapshot(JsonNode spec) {
        List<String> main = new ArrayList<>();
        if (spec != null) {
            String hard = text(spec, "hardBindingId");
            String primary = text(spec, "primaryBindingId");
            if (hard != null) {
                main.add(hard);
            } else if (primary != null) {
                main.add(primary);
            }
            String chain = text(spec, "fallbackChain");
            if (chain != null) {
                Arrays.stream(chain.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).forEach(main::add);
            } else {
                String fallback = text(spec, "fallbackBindingId");
                if (fallback != null) {
                    main.add(fallback);
                }
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        if (!main.isEmpty()) {
            groups.put("main", List.copyOf(main));
        }
        return new RoutingPlanSnapshot(true, RoutingMode.FIXED, groups, Map.of(), Map.of(),
                null, null, true);
    }

    /** 由投影行构造 V1 平铺 spec（版本框架未装配时的兜底，与 ModelHubSpecs.planSpec 同构）。 */
    public static JsonNode flatSpec(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                    com.tioka.model.domain.ModelEntities.RoutingPlan plan) {
        return ModelHubSpecs.planSpec(mapper, plan.name(), plan.type(), plan.primaryBindingId(),
                plan.fallbackBindingId(), plan.hardBindingId(), plan.fallbackChain());
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String v = n.asText(null);
        return v == null || v.isBlank() ? null : v.trim();
    }
}

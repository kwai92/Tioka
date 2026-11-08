package com.tioka.flow.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FLW-02：旧 Flow 版本快照中 LLM 节点的直连字段（baseUrl / apiKey / model 等）
 * 在加载时剥离，映射为 {@code routingPlanId}（空 = FLOW 默认方案），并记 warn。
 */
public final class LlmNodeConfigMigrator {
    private static final Logger log = LoggerFactory.getLogger(LlmNodeConfigMigrator.class);

    private static final Set<String> LEGACY_KEYS = Set.of(
            "baseurl", "apikey", "apikeyref", "model", "modelname", "modelref");

    private LlmNodeConfigMigrator() {}

    /**
     * 返回迁移后的图副本；非 LLM 节点原样拷贝。不修改入参。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> migrate(Map<String, Object> graph) {
        if (graph == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(graph);
        Object rawNodes = graph.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return out;
        }
        List<Map<String, Object>> migrated = new ArrayList<>(nodes.size());
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> rawNode)) {
                continue;
            }
            Map<String, Object> node = copyMap(rawNode);
            if ("LLM".equals(String.valueOf(node.get("type")))) {
                Object cfg = node.get("config");
                if (cfg instanceof Map<?, ?> rawCfg) {
                    String nodeId = String.valueOf(node.getOrDefault("id", "?"));
                    node.put("config", migrateLlmConfig(nodeId, copyMap(rawCfg)));
                }
            }
            migrated.add(node);
        }
        out.put("nodes", migrated);
        return out;
    }

    static Map<String, Object> migrateLlmConfig(String nodeId, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean hadLegacy = false;
        for (Map.Entry<String, Object> e : config.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            if (LEGACY_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                hadLegacy = true;
                continue;
            }
            out.put(key, e.getValue());
        }
        Object rp = out.get("routingPlanId");
        if (rp != null && String.valueOf(rp).isBlank()) {
            out.remove("routingPlanId");
            rp = null;
        }
        if (hadLegacy) {
            if (rp == null) {
                log.warn("Flow LLM 节点 {} 含直连配置(baseUrl/apiKey/model)，已映射为 FLOW 默认路由方案（routingPlanId 为空）",
                        nodeId);
            } else {
                log.warn("Flow LLM 节点 {} 含直连配置(baseUrl/apiKey/model)，已忽略；保留 routingPlanId={}",
                        nodeId, rp);
            }
        }
        return out;
    }

    private static Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                m.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return m;
    }
}

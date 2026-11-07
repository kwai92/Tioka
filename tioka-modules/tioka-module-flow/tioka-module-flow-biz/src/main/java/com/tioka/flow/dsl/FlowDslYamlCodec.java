package com.tioka.flow.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.tioka.flow.api.dsl.FlowDslDocument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL JSON/YAML 编解码（新包，不碰旧引擎）。
 * <p>使用 JsonMapper / YAMLMapper，避免直接 {@code new ObjectMapper()}（ENG-05 ArchUnit）。
 */
public class FlowDslYamlCodec {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public FlowDslYamlCodec() {
        this.yamlMapper = YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.jsonMapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    public String toYaml(FlowDslDocument doc) {
        try {
            return yamlMapper.writeValueAsString(toMap(doc));
        } catch (Exception e) {
            throw new IllegalStateException("yaml encode failed", e);
        }
    }

    public FlowDslDocument fromYaml(String yaml) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = yamlMapper.readValue(yaml, Map.class);
            return fromMap(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("yaml decode failed", e);
        }
    }

    public String toJson(FlowDslDocument doc) {
        try {
            return jsonMapper.writeValueAsString(toMap(doc));
        } catch (Exception e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    public FlowDslDocument fromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = jsonMapper.readValue(json, Map.class);
            return fromMap(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("json decode failed", e);
        }
    }

    private static Map<String, Object> toMap(FlowDslDocument doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", doc.schemaVersion());
        m.put("meta", doc.meta() == null ? Map.of() : doc.meta());
        m.put("inputs", doc.inputs() == null ? List.of() : doc.inputs());
        m.put("outputs", doc.outputs() == null ? List.of() : doc.outputs());
        if (doc.constants() != null && !doc.constants().isEmpty()) {
            m.put("constants", doc.constants());
        }
        if (doc.settings() != null && !doc.settings().isEmpty()) {
            m.put("settings", doc.settings());
        }
        m.put("nodes", doc.nodes() == null ? List.of() : doc.nodes());
        m.put("edges", doc.edges() == null ? List.of() : doc.edges());
        if (doc.subgraphs() != null && !doc.subgraphs().isEmpty()) {
            m.put("subgraphs", doc.subgraphs());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static FlowDslDocument fromMap(Map<String, Object> map) {
        int version = ((Number) map.getOrDefault("schemaVersion", 1)).intValue();
        Map<String, Object> meta = (Map<String, Object>) map.getOrDefault("meta", Map.of());
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) map.getOrDefault("inputs", List.of());
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) map.getOrDefault("outputs", List.of());
        Map<String, Object> constants = (Map<String, Object>) map.getOrDefault("constants", Map.of());
        Map<String, Object> settings = (Map<String, Object>) map.getOrDefault("settings", Map.of());
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.getOrDefault("nodes", List.of());
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.getOrDefault("edges", List.of());
        Map<String, Object> subgraphs = (Map<String, Object>) map.getOrDefault("subgraphs", Map.of());
        return new FlowDslDocument(
                version,
                meta == null ? Map.of() : meta,
                inputs == null ? List.of() : inputs,
                outputs == null ? List.of() : outputs,
                constants == null ? Map.of() : constants,
                settings == null ? Map.of() : settings,
                nodes == null ? List.of() : nodes,
                edges == null ? List.of() : edges,
                subgraphs == null ? Collections.emptyMap() : subgraphs
        );
    }
}

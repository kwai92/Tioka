package com.tioka.flow.dsl;

import com.tioka.flow.api.dsl.FlowDslDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * YAML ↔ DSL 文档往返（ADR-043 §3.1）。
 */
class FlowYamlRoundTripTest {

    @Test
    void yamlRoundTripPreservesDocument() {
        FlowDslYamlCodec codec = new FlowDslYamlCodec();
        FlowDslDocument original = sample();
        String yaml = codec.toYaml(original);
        assertFalse(yaml.isBlank());
        FlowDslDocument back = codec.fromYaml(yaml);
        assertEquals(original.schemaVersion(), back.schemaVersion());
        assertEquals(original.meta().get("name"), back.meta().get("name"));
        assertEquals(original.nodes().size(), back.nodes().size());
        assertEquals(original.edges().size(), back.edges().size());
        assertEquals(original.nodes().get(0).get("id"), back.nodes().get(0).get("id"));
        assertEquals(original.edges().get(0).get("fromNode"), back.edges().get(0).get("fromNode"));
    }

    @Test
    void jsonRoundTripPreservesCanonicalFields() {
        FlowDslYamlCodec codec = new FlowDslYamlCodec();
        FlowDslDocument original = sample();
        String json = codec.toJson(original);
        FlowDslDocument back = codec.fromJson(json);
        assertEquals(codec.toJson(original), codec.toJson(back));
    }

    private static FlowDslDocument sample() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("id", "in");
        in.put("type", "input");
        in.put("typeVersion", 1);
        in.put("label", "输入");
        in.put("config", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "out");
        out.put("type", "output");
        out.put("typeVersion", 1);
        out.put("label", "输出");
        out.put("config", Map.of());
        nodes.add(in);
        nodes.add(out);
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fromNode", "in");
        e.put("fromPort", "out");
        e.put("toNode", "out");
        e.put("toPort", "in");
        edges.add(e);
        return new FlowDslDocument(
                1,
                Map.of("name", "roundtrip", "owner", "qa"),
                List.of(Map.of("name", "q", "type", "text", "required", true)),
                List.of(Map.of("name", "a", "type", "text")),
                Map.of("greeting", "hi"),
                Map.of("runTimeout", "30s"),
                nodes,
                edges,
                Map.of()
        );
    }
}

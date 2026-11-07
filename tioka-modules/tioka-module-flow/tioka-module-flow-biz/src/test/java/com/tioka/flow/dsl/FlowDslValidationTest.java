package com.tioka.flow.dsl;

import com.tioka.flow.api.dsl.FlowDslDocument;
import com.tioka.flow.api.dsl.FlowDslValidator;
import com.tioka.flow.api.dsl.FlowPublishContext;
import com.tioka.flow.api.dsl.ValidationResult;
import com.tioka.flow.api.dsl.ValidationSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-043 §3.7 发布校验清单：每项至少一例。
 */
class FlowDslValidationTest {

    private FlowDslValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultFlowDslValidator();
    }

    @Test
    void structure_cycleIsError() {
        FlowDslDocument doc = baseFlow();
        doc.edges().add(edge("out", "out", "in", "in"));
        doc.edges().add(edge("in", "out", "out", "in"));
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "STRUCTURE_CYCLE");
    }

    @Test
    void structure_requiresSingleInputAndAtLeastOneOutput() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().removeIf(n -> "input".equals(n.get("type")));
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "STRUCTURE_INPUT");

        FlowDslDocument noOut = baseFlow();
        noOut.nodes().removeIf(n -> "output".equals(n.get("type")));
        noOut.edges().removeIf(e -> "out".equals(e.get("toNode")));
        assertError(validator.validate(noOut, FlowPublishContext.permissive()), "STRUCTURE_OUTPUT");
    }

    @Test
    void structure_unreachableFromInputIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("orphan", "transform", Map.of("expr", "1")));
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "STRUCTURE_UNREACHABLE");
    }

    @Test
    void structure_subgraphNestingOver3IsError() {
        FlowDslDocument doc = baseFlow();
        // 嵌套深度：sg1→sg2→sg3→sg4 = 4 > 3
        doc = withSubgraphs(doc, Map.of(
                "sg1", nestedSubgraph("sg2"),
                "sg2", nestedSubgraph("sg3"),
                "sg3", nestedSubgraph("sg4"),
                "sg4", nestedSubgraph(null)
        ));
        doc.nodes().add(node("loop1", "loop", Map.of("subgraph", "sg1", "maxIterations", 2)));
        wireThrough(doc, "loop1");
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "STRUCTURE_SUBGRAPH_DEPTH");
    }

    @Test
    void type_portIncompatibleIsError() {
        FlowDslDocument doc = baseFlow();
        // input.out=text → json_accept.in=json 禁止
        doc.nodes().add(node("t", "json_accept", Map.of()));
        wireThrough(doc, "t");
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "TYPE_PORT");
    }

    @Test
    void type_expressionBadRefIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().get(1).put("config", Map.of("expr", "${nodes.missing.out}"));
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "TYPE_EXPR");
    }

    @Test
    void refs_missingModelAliasIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("llm1", "llm", Map.of("model", "alias.gone", "prompt", "p@1")));
        wireThrough(doc, "llm1");
        FlowPublishContext ctx = new FlowPublishContext() {
            @Override
            public boolean modelAliasExists(String alias, String purpose) {
                return false;
            }

            @Override
            public boolean promptTemplatePublished(String ref) {
                return true;
            }

            @Override
            public boolean resourceExists(String kind, String ref) {
                return true;
            }

            @Override
            public boolean publisherCanUse(String kind, String ref) {
                return true;
            }
        };
        assertError(validator.validate(doc, ctx), "REF_MODEL");
    }

    @Test
    void permission_missingUseIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("kb1", "knowledge", Map.of("kb", "kb.demo")));
        wireThrough(doc, "kb1");
        FlowPublishContext ctx = new FlowPublishContext() {
            @Override
            public boolean modelAliasExists(String alias, String purpose) {
                return true;
            }

            @Override
            public boolean promptTemplatePublished(String ref) {
                return true;
            }

            @Override
            public boolean resourceExists(String kind, String ref) {
                return true;
            }

            @Override
            public boolean publisherCanUse(String kind, String ref) {
                return false;
            }
        };
        assertError(validator.validate(doc, ctx), "PERM_USE");
    }

    @Test
    void subflow_cycleIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("sf", "subflow", Map.of("flowRef", "flow.a@1")));
        wireThrough(doc, "sf");
        FlowPublishContext ctx = new FlowPublishContext() {
            @Override
            public boolean modelAliasExists(String alias, String purpose) {
                return true;
            }

            @Override
            public boolean promptTemplatePublished(String ref) {
                return true;
            }

            @Override
            public boolean resourceExists(String kind, String ref) {
                return true;
            }

            @Override
            public boolean publisherCanUse(String kind, String ref) {
                return true;
            }

            @Override
            public Set<String> subflowEdges() {
                // flow.a@1 → flow.b@1 → flow.a@1
                return Set.of("flow.a@1->flow.b@1", "flow.b@1->flow.a@1");
            }
        };
        assertError(validator.validate(doc, ctx), "SUBFLOW_CYCLE");
    }

    @Test
    void limits_loopWithoutMaxIterationsIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("loop1", "loop", Map.of("subgraph", "body")));
        wireThrough(doc, "loop1");
        doc = withSubgraphs(doc, Map.of("body", nestedSubgraph(null)));
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "LIMIT_LOOP");
    }

    @Test
    void limits_nodeCountOver200IsError() {
        FlowDslDocument doc = baseFlow();
        for (int i = 0; i < 200; i++) {
            String id = "n" + i;
            doc.nodes().add(node(id, "transform", Map.of("expr", "1")));
            doc.edges().add(edge(i == 0 ? "in" : "n" + (i - 1), "out", id, "in"));
        }
        doc.edges().add(edge("n199", "out", "out", "in"));
        // base 已有 in/out，再加 200 → 202
        assertError(validator.validate(doc, FlowPublishContext.permissive()), "LIMIT_NODE_COUNT");
    }

    @Test
    void publication_toolWithAgentExceedsSyncCapIsError() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("agent1", "agent", Map.of("agentRef", "ag@1", "timeout", "120s")));
        wireThrough(doc, "agent1");
        FlowPublishContext ctx = new FlowPublishContext() {
            @Override
            public String publicationKind() {
                return "TOOL";
            }

            @Override
            public long toolSyncMaxSeconds() {
                return 60L;
            }

            @Override
            public boolean modelAliasExists(String alias, String purpose) {
                return true;
            }

            @Override
            public boolean promptTemplatePublished(String ref) {
                return true;
            }

            @Override
            public boolean resourceExists(String kind, String ref) {
                return true;
            }

            @Override
            public boolean publisherCanUse(String kind, String ref) {
                return true;
            }
        };
        assertError(validator.validate(doc, ctx), "PUB_SYNC_CAP");
    }

    @Test
    void quality_anyPortIsWarning() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("g", "any_gate", Map.of()));
        wireThrough(doc, "g");
        ValidationResult r = validator.validate(doc, FlowPublishContext.permissive());
        assertTrue(r.hasCode("QUALITY_ANY"), () -> r.issues().toString());
        assertTrue(r.warnings().stream().anyMatch(i -> "QUALITY_ANY".equals(i.code())));
        assertFalse(r.hasErrors());
    }

    @Test
    void quality_deprecatedNodeTypeIsWarning() {
        FlowDslDocument doc = baseFlow();
        doc.nodes().add(node("old", "legacy_http", Map.of("url", "https://example.com")));
        wireThrough(doc, "old");
        ValidationResult r = validator.validate(doc, FlowPublishContext.permissive());
        assertTrue(r.hasCode("QUALITY_DEPRECATED"), () -> r.issues().toString());
        assertTrue(r.warnings().stream().anyMatch(i -> i.severity() == ValidationSeverity.WARNING));
    }

    @Test
    void validMinimalFlowPasses() {
        ValidationResult r = validator.validate(baseFlow(), FlowPublishContext.permissive());
        assertFalse(r.hasErrors(), () -> r.issues().toString());
    }

    private static void assertError(ValidationResult r, String code) {
        assertTrue(r.hasErrors(), () -> "expected errors, got: " + r.issues());
        assertTrue(r.hasCode(code), () -> "expected " + code + " in " + r.issues());
    }

    private static FlowDslDocument baseFlow() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("in", "input", Map.of()));
        nodes.add(node("out", "output", Map.of()));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("in", "out", "out", "in"));
        return new FlowDslDocument(
                1,
                Map.of("name", "demo"),
                List.of(Map.of("name", "x", "type", "text", "required", true)),
                List.of(Map.of("name", "y", "type", "text")),
                Map.of(),
                Map.of("runTimeout", "30s"),
                nodes,
                edges,
                Map.of()
        );
    }

    private static Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("typeVersion", 1);
        n.put("label", id);
        n.put("config", new LinkedHashMap<>(config));
        return n;
    }

    private static Map<String, Object> edge(String from, String fromPort, String to, String toPort) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fromNode", from);
        e.put("fromPort", fromPort);
        e.put("toNode", to);
        e.put("toPort", toPort);
        return e;
    }

    private static void wireThrough(FlowDslDocument doc, String mid) {
        doc.edges().clear();
        doc.edges().add(edge("in", "out", mid, "in"));
        doc.edges().add(edge(mid, "out", "out", "in"));
    }

    private static Map<String, Object> nestedSubgraph(String childId) {
        Map<String, Object> sg = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("in", "input", Map.of()));
        nodes.add(node("out", "output", Map.of()));
        if (childId != null) {
            nodes.add(node("child", "loop", Map.of("subgraph", childId, "maxIterations", 1)));
        }
        sg.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        if (childId != null) {
            edges.add(edge("in", "out", "child", "in"));
            edges.add(edge("child", "out", "out", "in"));
        } else {
            edges.add(edge("in", "out", "out", "in"));
        }
        sg.put("edges", edges);
        return sg;
    }

    private static FlowDslDocument withSubgraphs(FlowDslDocument doc, Map<String, Object> subgraphs) {
        return new FlowDslDocument(
                doc.schemaVersion(),
                doc.meta(),
                doc.inputs(),
                doc.outputs(),
                doc.constants(),
                doc.settings(),
                doc.nodes(),
                doc.edges(),
                new HashMap<>(subgraphs)
        );
    }
}

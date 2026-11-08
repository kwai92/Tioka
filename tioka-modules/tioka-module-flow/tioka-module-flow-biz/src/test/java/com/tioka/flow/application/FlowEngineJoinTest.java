package com.tioka.flow.application;

import com.tioka.flow.application.exec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FLW-01：Branch 后汇合 / SKIPPED 传播 / 并行汇合回归。
 */
class FlowEngineJoinTest {
    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        List<NodeExecutor> executors = List.of(
                new IoNodeExecutor(),
                new OutputNodeExecutor(),
                new PromptNodeExecutor(),
                new BranchNodeExecutor()
        );
        engine = new FlowEngine(new NodeExecutorRegistry(executors), provider(null));
    }

    @Test
    void branchJoin_truePath_executesJoinAndSkipsFalseBranch() {
        // Start → Branch → (A | B) → Join → Output
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "start", "type", "IO"),
                        Map.of("id", "branch", "type", "Branch", "config", Map.of("field", "route", "equals", "A")),
                        Map.of("id", "a", "type", "Prompt", "config", Map.of("template", "path-A")),
                        Map.of("id", "b", "type", "Prompt", "config", Map.of("template", "path-B")),
                        Map.of("id", "join", "type", "IO"),
                        Map.of("id", "out", "type", "Output")
                ),
                "edges", List.of(
                        Map.of("from", "start", "to", "branch"),
                        Map.of("from", "branch", "to", "a", "when", "true"),
                        Map.of("from", "branch", "to", "b", "when", "false"),
                        Map.of("from", "a", "to", "join"),
                        Map.of("from", "b", "to", "join"),
                        Map.of("from", "join", "to", "out")
                )
        );

        FlowEngine.EngineResult r = engine.run("fr_join_true", graph, Map.of("route", "A"));

        assertEquals("COMPLETED", r.status());
        assertExecuted(r, "start", "branch", "a", "join", "out");
        assertSkipped(r, "b");
        assertEquals(1, countExecuted(r, "join"));
        assertEquals(1, countExecuted(r, "out"));
        assertNotNull(r.output());
    }

    @Test
    void nestedBranchJoins_eachJoinRunsOnce() {
        // Start → B1 → (A | X) ; A → B2 → (C | D) → J2 → J1 → Output
        // X → J1（与 J2 汇合后再出）
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "start", "type", "IO"),
                        Map.of("id", "b1", "type", "Branch", "config", Map.of("field", "outer", "equals", "L")),
                        Map.of("id", "a", "type", "Prompt", "config", Map.of("template", "left")),
                        Map.of("id", "x", "type", "Prompt", "config", Map.of("template", "right")),
                        Map.of("id", "b2", "type", "Branch", "config", Map.of("field", "inner", "equals", "T")),
                        Map.of("id", "c", "type", "Prompt", "config", Map.of("template", "inner-T")),
                        Map.of("id", "d", "type", "Prompt", "config", Map.of("template", "inner-F")),
                        Map.of("id", "j2", "type", "IO"),
                        Map.of("id", "j1", "type", "IO"),
                        Map.of("id", "out", "type", "Output")
                ),
                "edges", List.of(
                        Map.of("from", "start", "to", "b1"),
                        Map.of("from", "b1", "to", "a", "when", "true"),
                        Map.of("from", "b1", "to", "x", "when", "false"),
                        Map.of("from", "a", "to", "b2"),
                        Map.of("from", "b2", "to", "c", "when", "true"),
                        Map.of("from", "b2", "to", "d", "when", "false"),
                        Map.of("from", "c", "to", "j2"),
                        Map.of("from", "d", "to", "j2"),
                        Map.of("from", "j2", "to", "j1"),
                        Map.of("from", "x", "to", "j1"),
                        Map.of("from", "j1", "to", "out")
                )
        );

        FlowEngine.EngineResult r = engine.run("fr_join_nested", graph, Map.of("outer", "L", "inner", "T"));

        assertEquals("COMPLETED", r.status());
        assertExecuted(r, "start", "b1", "a", "b2", "c", "j2", "j1", "out");
        assertSkipped(r, "x", "d");
        assertEquals(1, countExecuted(r, "j1"));
        assertEquals(1, countExecuted(r, "j2"));
        assertEquals(1, countExecuted(r, "out"));
    }

    @Test
    void allBranchesUnmatched_downstreamSkipped_runSucceeds() {
        // Start → Branch → (only when=true → A) → Output；条件为假则下游全 SKIPPED
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "start", "type", "IO"),
                        Map.of("id", "branch", "type", "Branch", "config", Map.of("field", "route", "equals", "A")),
                        Map.of("id", "a", "type", "Prompt", "config", Map.of("template", "only-true")),
                        Map.of("id", "out", "type", "Output")
                ),
                "edges", List.of(
                        Map.of("from", "start", "to", "branch"),
                        Map.of("from", "branch", "to", "a", "when", "true"),
                        Map.of("from", "a", "to", "out")
                )
        );

        FlowEngine.EngineResult r = engine.run("fr_join_none", graph, Map.of("route", "B"));

        assertEquals("COMPLETED", r.status());
        assertNull(r.output());
        assertExecuted(r, "start", "branch");
        assertSkipped(r, "a", "out");
        assertTrue(r.trace().stream().anyMatch(t ->
                "out".equals(t.get("id")) && "SKIPPED".equals(t.get("status"))));
    }

    @Test
    void parallelJoinWithoutBranch_behavesAsBefore() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "a", "type", "IO", "config", Map.of("map", List.of("x"))),
                        Map.of("id", "b", "type", "IO", "config", Map.of("map", List.of("y"))),
                        Map.of("id", "c", "type", "Output")
                ),
                "edges", List.of(
                        Map.of("from", "a", "to", "c"),
                        Map.of("from", "b", "to", "c")
                )
        );

        FlowEngine.EngineResult r = engine.run("fr_join_par", graph, Map.of("x", 1, "y", 2));

        assertEquals("COMPLETED", r.status());
        assertExecuted(r, "a", "b", "c");
        assertTrue(r.trace().stream().noneMatch(t -> "SKIPPED".equals(t.get("status"))));
        assertTrue(r.trace().stream().anyMatch(t -> t.containsKey("parallelGroup")));
        assertNotNull(r.output());
    }

    private static void assertExecuted(FlowEngine.EngineResult r, String... ids) {
        Set<String> executed = r.trace().stream()
                .filter(t -> t.containsKey("id") && !"SKIPPED".equals(t.get("status")))
                .map(t -> String.valueOf(t.get("id")))
                .collect(Collectors.toSet());
        for (String id : ids) {
            assertTrue(executed.contains(id), () -> "expected executed: " + id + ", got " + executed);
        }
    }

    private static void assertSkipped(FlowEngine.EngineResult r, String... ids) {
        Set<String> skipped = r.trace().stream()
                .filter(t -> "SKIPPED".equals(t.get("status")))
                .map(t -> String.valueOf(t.get("id")))
                .collect(Collectors.toSet());
        for (String id : ids) {
            assertTrue(skipped.contains(id), () -> "expected SKIPPED: " + id + ", got " + skipped);
        }
    }

    private static long countExecuted(FlowEngine.EngineResult r, String id) {
        return r.trace().stream()
                .filter(t -> id.equals(t.get("id")) && !"SKIPPED".equals(t.get("status")))
                .count();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }
}

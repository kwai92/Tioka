package com.tioka.flow.application;

import com.tioka.flow.application.exec.*;
import com.tioka.framework.error.BizException;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {
    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        ModelGateway mockGw = new ModelGateway() {
            @Override
            public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
                String content = "";
                if (request != null && request.messages() != null && !request.messages().isEmpty()) {
                    content = String.valueOf(request.messages().get(request.messages().size() - 1).get("content"));
                }
                return new ChatResult("mock:" + content, null, List.of(), 1, 1, "bnd_mock", "mock@m", 0, "stop");
            }

            @Override
            public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
                return new EmbeddingResult(List.of(new float[]{0.1f}), 1, 1, "bnd_mock");
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                return new RerankResult(List.of(), "bnd_mock");
            }
        };
        ObjectProvider<ModelGateway> gwProvider = provider(mockGw);
        List<NodeExecutor> executors = List.of(
                new IoNodeExecutor(),
                new OutputNodeExecutor(),
                new PromptNodeExecutor(),
                new EmbeddingNodeExecutor(),
                new ParserNodeExecutor(),
                new BranchNodeExecutor(),
                new LoopNodeExecutor(),
                new LlmNodeExecutor(gwProvider, provider(null)),
                new AgentNodeExecutor(provider(null), provider(null)),
                new RetrieveNodeExecutor(provider(null)),
                new HttpNodeExecutor(provider(null), provider(null),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                new McpNodeExecutor(provider(null)),
                new HumanCardNodeExecutor(provider(null))
        );
        engine = new FlowEngine(new NodeExecutorRegistry(executors));
    }

    @Test
    void sequentialIoPromptOutput() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "IO"),
                        Map.of("id", "n2", "type", "Prompt", "config", Map.of("template", "Hello {{name}}")),
                        Map.of("id", "n3", "type", "Output")
                ),
                "edges", List.of()
        );
        FlowEngine.EngineResult r = engine.run("fr_seq", graph, Map.of("name", "Tioka"));
        assertEquals("COMPLETED", r.status());
        assertNotNull(r.output());
        assertEquals(3, r.trace().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> promptOut = (Map<String, Object>) r.trace().get(1).get("output");
        assertEquals("Hello Tioka", promptOut.get("prompt"));
    }

    @Test
    void branchTruePath() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "IO"),
                        Map.of("id", "n2", "type", "Branch", "config", Map.of("field", "route", "equals", "A")),
                        Map.of("id", "n3", "type", "LLM"),
                        Map.of("id", "n4", "type", "HTTP")
                ),
                "edges", List.of(
                        Map.of("from", "n1", "to", "n2"),
                        Map.of("from", "n2", "to", "n3", "when", "true"),
                        Map.of("from", "n2", "to", "n4", "when", "false")
                )
        );
        FlowEngine.EngineResult r = engine.run("fr_br", graph, Map.of("route", "A"));
        assertEquals("COMPLETED", r.status());
        assertTrue(r.trace().stream().anyMatch(t -> "LLM".equals(t.get("type"))));
        assertTrue(r.trace().stream().noneMatch(t -> "HTTP".equals(t.get("type"))));
    }

    @Test
    void branchFalsePath() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "IO"),
                        Map.of("id", "n2", "type", "Branch", "config", Map.of("field", "route", "equals", "A")),
                        Map.of("id", "n3", "type", "LLM"),
                        Map.of("id", "n4", "type", "HTTP")
                ),
                "edges", List.of(
                        Map.of("from", "n1", "to", "n2"),
                        Map.of("from", "n2", "to", "n3", "when", "true"),
                        Map.of("from", "n2", "to", "n4", "when", "false")
                )
        );
        FlowEngine.EngineResult r = engine.run("fr_br2", graph, Map.of("route", "B"));
        assertEquals("COMPLETED", r.status());
        assertTrue(r.trace().stream().anyMatch(t -> "HTTP".equals(t.get("type"))));
        assertTrue(r.trace().stream().noneMatch(t -> "LLM".equals(t.get("type"))));
    }

    @Test
    void parallelIndependentIoNodes() {
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
        FlowEngine.EngineResult r = engine.run("fr_par", graph, Map.of("x", 1, "y", 2));
        assertEquals("COMPLETED", r.status());
        assertTrue(r.trace().stream().anyMatch(t -> "a".equals(t.get("id"))));
        assertTrue(r.trace().stream().anyMatch(t -> "b".equals(t.get("id"))));
        assertTrue(r.trace().stream().anyMatch(t -> t.containsKey("parallelGroup")));
    }

    @Test
    void failureMarksFailedAndTraceHasError() {
        NodeExecutor boomLlm = new NodeExecutor() {
            @Override public String type() { return "LLM"; }
            @Override public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
                throw new IllegalStateException("boom");
            }
        };
        FlowEngine failing = new FlowEngine(new NodeExecutorRegistry(List.of(
                new IoNodeExecutor(), boomLlm
        )));
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "IO"),
                        Map.of("id", "n2", "type", "LLM")
                ),
                "edges", List.of()
        );
        FlowEngine.EngineResult r = failing.run("fr_fail", graph, Map.of());
        assertEquals("FAILED", r.status());
        assertTrue(r.trace().stream().anyMatch(t -> t.containsKey("error")));
    }

    @Test
    void rejectWildApiKey() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "LLM", "config", Map.of("apiKey", "sk-wild"))
                ),
                "edges", List.of()
        );
        assertThrows(BizException.class, () -> engine.run("fr_key", graph, Map.of()));
    }

    @Test
    void rejectWildTokenInHttp() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
                Map.of("id", "n1", "type", "HTTP",
                        "config", Map.of("url", "https://example.com", "token", "plain-token"))
        ));
        graph.put("edges", List.of());
        BizException ex = assertThrows(BizException.class, () -> engine.run("fr_tok", graph, Map.of()));
        assertTrue(ex.getMessage().contains("明文凭据") || ex.getMessage().contains("token"));
    }

    @Test
    void validateCycleRejected() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "a", "type", "IO"),
                        Map.of("id", "b", "type", "IO")
                ),
                "edges", List.of(
                        Map.of("from", "a", "to", "b"),
                        Map.of("from", "b", "to", "a")
                )
        );
        assertThrows(BizException.class, () -> FlowGraphValidator.validate(graph, FlowService.NODE_TYPES));
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

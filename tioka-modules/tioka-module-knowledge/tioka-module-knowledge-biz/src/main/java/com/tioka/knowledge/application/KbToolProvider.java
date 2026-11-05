package com.tioka.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KbToolProvider implements ToolProvider {
    private final KnowledgeService knowledgeService;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbToolProvider(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() { return "knowledge"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(
                new ToolDescriptor(
                        "kb.search",
                        "Search knowledge base",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "kbId", Map.of("type", "string"),
                                        "query", Map.of("type", "string"),
                                        "topK", Map.of("type", "integer")),
                                "required", List.of("kbId", "query"))),
                new ToolDescriptor(
                        "kb.read_chunk",
                        "Read a knowledge chunk by chunkId",
                        Map.of("type", "object",
                                "properties", Map.of("chunkId", Map.of("type", "string")),
                                "required", List.of("chunkId"))),
                new ToolDescriptor(
                        "kb.read_document",
                        "Read document text by docId (optional maxChunks)",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "docId", Map.of("type", "string"),
                                        "maxChunks", Map.of("type", "integer")),
                                "required", List.of("docId")))
        );
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case "kb.search" -> invokeSearch(arguments);
                case "kb.read_chunk" -> invokeReadChunk(arguments);
                case "kb.read_document" -> invokeReadDocument(arguments);
                default -> new ToolResult(false, "unknown tool");
            };
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private ToolResult invokeSearch(Map<String, Object> arguments) throws Exception {
        String kbId = String.valueOf(arguments.get("kbId"));
        String query = String.valueOf(arguments.get("query"));
        int topK = arguments.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(arguments.get("topK")));
        var hits = knowledgeService.search(kbId, query, topK);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("citationId", h.citationId());
            m.put("chunkId", h.chunkId());
            m.put("docId", h.docId());
            m.put("text", h.text());
            m.put("score", h.score());
            m.put("title", h.title());
            out.add(m);
        }
        return new ToolResult(true, mapper.writeValueAsString(out));
    }

    private ToolResult invokeReadChunk(Map<String, Object> arguments) throws Exception {
        String chunkId = String.valueOf(arguments.get("chunkId"));
        var c = knowledgeService.readChunk(chunkId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", c.chunkId());
        m.put("docId", c.docId());
        m.put("kbId", c.kbId());
        m.put("text", c.text());
        m.put("ordinal", c.ordinal());
        m.put("title", c.title());
        return new ToolResult(true, mapper.writeValueAsString(m));
    }

    private ToolResult invokeReadDocument(Map<String, Object> arguments) throws Exception {
        String docId = String.valueOf(arguments.get("docId"));
        int maxChunks = arguments.get("maxChunks") == null ? 0 : Integer.parseInt(String.valueOf(arguments.get("maxChunks")));
        var d = knowledgeService.readDocument(docId, maxChunks);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", d.docId());
        m.put("kbId", d.kbId());
        m.put("title", d.title());
        m.put("text", d.text());
        m.put("chunkIds", d.chunkIds());
        return new ToolResult(true, mapper.writeValueAsString(m));
    }
}

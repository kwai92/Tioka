package com.tioka.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Permissions;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KbToolProvider implements ToolProvider {
    private final KnowledgeService knowledgeService;
    private final ObjectMapper mapper;

    public KbToolProvider(KnowledgeService knowledgeService, ObjectMapper mapper) {
        this.mapper = mapper;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() { return "knowledge"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(
                ToolDescriptor.builder()
                        .name("kb.search")
                        .description("Search knowledge base")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "kbId", Map.of("type", "string"),
                                        "kbIds", Map.of("type", "array", "items", Map.of("type", "string")),
                                        "query", Map.of("type", "string"),
                                        "topK", Map.of("type", "integer")),
                                "required", List.of("query")))
                        .build(),
                ToolDescriptor.builder()
                        .name("kb.read_chunk")
                        .description("Read a knowledge chunk by chunkId")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("chunkId", Map.of("type", "string")),
                                "required", List.of("chunkId")))
                        .build(),
                ToolDescriptor.builder()
                        .name("kb.read_document")
                        .description("Read document text by docId (optional maxChunks)")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "docId", Map.of("type", "string"),
                                        "maxChunks", Map.of("type", "integer")),
                                "required", List.of("docId")))
                        .build()
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

    @SuppressWarnings("unchecked")
    private ToolResult invokeSearch(Map<String, Object> arguments) throws Exception {
        String query = String.valueOf(arguments.get("query"));
        int topK = arguments.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(arguments.get("topK")));
        List<String> kbIds = new ArrayList<>();
        Object multi = arguments.get("kbIds");
        if (multi instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    kbIds.add(String.valueOf(o));
                }
            }
        }
        if (arguments.get("kbId") != null && !String.valueOf(arguments.get("kbId")).isBlank()) {
            String id = String.valueOf(arguments.get("kbId"));
            if (!kbIds.contains(id)) {
                kbIds.add(id);
            }
        }
        // kbIds 空：检索当前主体全部可见知识库（KNW-02 / ADR-018）
        List<KnowledgeService.RetrievalHit> hits = knowledgeService.retrieve(
                kbIds.isEmpty() ? null : kbIds, query, topK);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("citationId", h.citationId());
            m.put("chunkId", h.chunkId());
            m.put("docId", h.docId());
            m.put("kbId", h.kbId());
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

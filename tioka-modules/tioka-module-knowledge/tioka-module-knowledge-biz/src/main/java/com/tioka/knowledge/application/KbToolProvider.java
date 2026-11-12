package com.tioka.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Permissions;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KNW-14：知识使用模式与 {@code kb.*} 工具（ADR-056 §3）。
 *
 * <p>模式来自 {@code AgentSpec.knowledge.mode}（经 {@code agentToolConfig} 传入，禁止反查 Agent）：
 * {@code AUTO} 不开放工具（由 D 区段提供者自动注入）、{@code TOOL} 只开放工具、
 * {@code BOTH} 两者都有。缺省 {@code TOOL} 与 {@code AgentSpec.KnowledgeBinding.empty()} 一致。
 *
 * <p>{@code kb.read} 按 ADR-056 取代 {@code kb.read_chunk} / {@code kb.read_document}；
 * 后两者保留为兼容别名，既有 Agent 不受影响。
 */
@Component
public class KbToolProvider implements ToolProvider {
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_TOOL = "TOOL";
    public static final String MODE_BOTH = "BOTH";

    private final KnowledgeService knowledgeService;
    private final ObjectProvider<GrantService> grants;
    private final ObjectMapper mapper;

    public KbToolProvider(KnowledgeService knowledgeService,
                          ObjectProvider<GrantService> grants,
                          ObjectMapper mapper) {
        this.mapper = mapper;
        this.knowledgeService = knowledgeService;
        this.grants = grants;
    }

    @Override
    public String name() { return "knowledge"; }

    /** 兼容旧调用：按缺省 TOOL 全量开放。 */
    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return allTools();
    }

    /** KNW-14：AUTO 模式不开放任何 kb.* 工具。 */
    @Override
    public List<ToolDescriptor> listTools(String agentVersionId, Map<String, Object> agentToolConfig) {
        return MODE_AUTO.equalsIgnoreCase(modeOf(agentToolConfig)) ? List.of() : allTools();
    }

    private static List<ToolDescriptor> allTools() {
        return List.of(
                ToolDescriptor.builder()
                        .name("kb.search")
                        .description("Search knowledge bases (multi-kb, metadata filter, topK)")
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
                        .name("kb.read")
                        .description("Read a chunk, its parent block, or a document page range")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "scope", Map.of("type", "string",
                                                "enum", List.of("chunk", "parent", "document")),
                                        "chunkId", Map.of("type", "string"),
                                        "docId", Map.of("type", "string"),
                                        "pageStart", Map.of("type", "integer"),
                                        "pageEnd", Map.of("type", "integer"),
                                        "maxChunks", Map.of("type", "integer"))))
                        .build(),
                ToolDescriptor.builder()
                        .name("kb.list")
                        .description("List knowledge bases available to this agent")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build(),
                // M1/M2 兼容别名：ADR-056 §3 由 kb.read 取代，保留以免存量 Agent 工具名失效。
                ToolDescriptor.builder()
                        .name("kb.read_chunk")
                        .description("Deprecated alias of kb.read(scope=chunk)")
                        .requiredPermission(Permissions.KB_READ)
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of("chunkId", Map.of("type", "string")),
                                "required", List.of("chunkId")))
                        .build(),
                ToolDescriptor.builder()
                        .name("kb.read_document")
                        .description("Deprecated alias of kb.read(scope=document)")
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

    static String modeOf(Map<String, Object> agentToolConfig) {
        Object raw = agentToolConfig == null ? null : agentToolConfig.get("knowledge");
        if (raw instanceof Map<?, ?> m && m.get("mode") != null) {
            String mode = String.valueOf(m.get("mode")).toUpperCase();
            if (MODE_AUTO.equals(mode) || MODE_TOOL.equals(mode) || MODE_BOTH.equals(mode)) {
                return mode;
            }
        }
        return MODE_TOOL;
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        return invoke(null, null, toolName, arguments, Map.of());
    }

    /**
     * DAT-02：kb.search 带调用方授予（grantId → maxDataLabel）做标签过滤；
     * 结果 ToolResult 携带命中最大有效标签（thread 标签随检索提升）。
     */
    @Override
    public ToolResult invoke(com.tioka.runtime.api.spi.ToolGateway.ToolCallContext ctx,
                             String agentVersionId, String toolName, Map<String, Object> arguments,
                             Map<String, Object> agentToolConfig) {
        com.tioka.framework.label.DataLabel maxLabel = null;
        if (ctx != null && ctx.grantId() != null && !ctx.grantId().isBlank()) {
            GrantService gs = grants.getIfAvailable();
            if (gs != null) {
                try {
                    var grant = gs.get(ctx.grantId());
                    if (grant != null) {
                        maxLabel = grant.maxDataLabel();
                    }
                } catch (RuntimeException ignored) {
                    // 授予不可用 = 不过滤（旧口径）
                }
            }
        }
        try {
            return switch (toolName) {
                case "kb.search" -> invokeSearch(arguments, maxLabel);
                case "kb.read" -> invokeRead(arguments);
                case "kb.list" -> invokeList();
                case "kb.read_chunk" -> invokeReadChunk(arguments);
                case "kb.read_document" -> invokeReadDocument(arguments);
                default -> new ToolResult(false, "unknown tool");
            };
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult invokeSearch(Map<String, Object> arguments,
                                    com.tioka.framework.label.DataLabel maxLabel) throws Exception {
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
                kbIds.isEmpty() ? null : kbIds, query, topK, maxLabel);
        List<Map<String, Object>> out = new ArrayList<>();
        com.tioka.framework.label.DataLabel resultLabel = null;
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
            com.tioka.framework.label.DataLabel eff = knowledgeService.effectiveLabel(h.kbId(), h.docId());
            resultLabel = eff == null ? resultLabel : com.tioka.framework.label.DataLabel.max(resultLabel, eff);
        }
        return new ToolResult(true, mapper.writeValueAsString(out),
                resultLabel == null ? null : resultLabel.name());
    }

    /** KNW-14：scope=chunk / parent / document；pageStart/pageEnd 与 maxChunks 为可选裁剪。 */
    private ToolResult invokeRead(Map<String, Object> arguments) throws Exception {
        String scope = arguments.get("scope") == null ? "document"
                : String.valueOf(arguments.get("scope"));
        int maxChunks = intOf(arguments.get("maxChunks"), 0);
        Integer pageStart = arguments.get("pageStart") == null ? null
                : Integer.parseInt(String.valueOf(arguments.get("pageStart")));
        Integer pageEnd = arguments.get("pageEnd") == null ? null
                : Integer.parseInt(String.valueOf(arguments.get("pageEnd")));
        Map<String, Object> m = new LinkedHashMap<>();
        switch (scope) {
            case "chunk", "parent" -> {
                String chunkId = String.valueOf(arguments.get("chunkId"));
                var c = "parent".equals(scope)
                        ? knowledgeService.readParentChunk(chunkId)
                        : knowledgeService.readChunk(chunkId);
                m.put("scope", scope);
                m.put("sourceChunkId", chunkId);
                m.put("chunkId", c.chunkId());
                m.put("docId", c.docId());
                m.put("kbId", c.kbId());
                m.put("text", c.text());
                m.put("ordinal", c.ordinal());
                m.put("title", c.title());
                if ("parent".equals(scope)) {
                    m.put("parentResolved", !chunkId.equals(c.chunkId()));
                }
            }
            case "document" -> {
                String docId = String.valueOf(arguments.get("docId"));
                var d = knowledgeService.readDocument(docId, maxChunks, pageStart, pageEnd);
                m.put("scope", scope);
                m.put("docId", d.docId());
                m.put("kbId", d.kbId());
                m.put("title", d.title());
                m.put("text", d.text());
                m.put("chunkIds", d.chunkIds());
            }
            default -> {
                return new ToolResult(false, "unsupported scope: " + scope);
            }
        }
        if (pageStart != null) m.put("pageStart", pageStart);
        if (pageEnd != null) m.put("pageEnd", pageEnd);
        return new ToolResult(true, mapper.writeValueAsString(m));
    }

    private ToolResult invokeList() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var kb : knowledgeService.listVisibleBases()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kbId", kb.kbId());
            m.put("name", kb.name());
            m.put("dataLabel", kb.dataLabel());
            out.add(m);
        }
        return new ToolResult(true, mapper.writeValueAsString(out));
    }

    private static int intOf(Object v, int fallback) {
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ToolResult invokeReadChunk(Map<String, Object> arguments) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        args.put("scope", "chunk");
        return invokeRead(args);
    }

    private ToolResult invokeReadDocument(Map<String, Object> arguments) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        args.put("scope", "document");
        return invokeRead(args);
    }
}

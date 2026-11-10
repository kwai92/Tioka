package com.tioka.ontology.application.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.doc.DocUnit;
import com.tioka.ontology.api.doc.DocumentApi;
import com.tioka.runtime.api.spi.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @doc.unit 工具（DOC-03，M3-03 §4）：返回该单元与前后各 N 个单元的文本、条款路径、页码。
 * 只读，NONE 可逆性。经 DocumentApi.units 取数，注册进 ToolGateway 注册表
 * （P3-00b 后 DefaultToolGateway 走注册表；KbToolProvider 先例）。
 * 不用 @Component：依赖的 DocumentApi 由 tioka.ontology.doc.enabled 条件装配
 * （OntologyDocAutoConfiguration），此处经该装配类 @Bean 注册。
 */
public class DocToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "doc.unit";
    public static final int DEFAULT_CONTEXT = 2;

    private final DocumentApi documents;
    private final ObjectMapper mapper;

    public DocToolProvider(DocumentApi documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "ontology";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(
                ToolDescriptor.builder()
                        .name(TOOL_NAME)
                        .description("按 unitId 读取文档单元及前后 N 个单元的文本、条款路径与页码（只读）")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "unitId", Map.of("type", "string"),
                                        "before", Map.of("type", "integer"),
                                        "after", Map.of("type", "integer")),
                                "required", List.of("unitId")))
                        // 只读：NONE 可逆性（M3-03 §4）
                        .build()
        );
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        if (!TOOL_NAME.equals(toolName)) {
            return new ToolResult(false, "unknown tool: " + toolName);
        }
        try {
            return invokeUnit(arguments);
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private ToolResult invokeUnit(Map<String, Object> arguments) throws Exception {
        String unitId = arguments.get("unitId") == null ? "" : String.valueOf(arguments.get("unitId"));
        if (unitId.isBlank()) {
            return new ToolResult(false, "unitId 必填");
        }
        int before = arguments.get("before") == null
                ? DEFAULT_CONTEXT : Integer.parseInt(String.valueOf(arguments.get("before")));
        int after = arguments.get("after") == null
                ? DEFAULT_CONTEXT : Integer.parseInt(String.valueOf(arguments.get("after")));

        // unitId 形如 {docId}:{pageNo}:{seq}；seq 为文档内全局顺序
        int sep = unitId.lastIndexOf(':');
        if (sep <= 0 || sep == unitId.length() - 1) {
            return new ToolResult(false, "unitId 格式错误（应为 {docId}:{pageNo}:{seq}）");
        }
        String docId = unitId.substring(0, unitId.lastIndexOf(':', sep - 1));
        int seq;
        try {
            seq = Integer.parseInt(unitId.substring(sep + 1));
        } catch (NumberFormatException e) {
            return new ToolResult(false, "unitId seq 非数字");
        }
        // 只读 NONE：未发现 unitId 对应文档时返回明确错误（权限过滤按调用方可见文档）
        List<DocUnit> window = documents.units(docId, Math.max(1, seq - before), seq + after);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocUnit u : window) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("unitId", u.unitId());
            m.put("seq", u.seq());
            m.put("pageNo", u.pageNo());
            m.put("kind", u.kind());
            m.put("text", u.text());
            m.put("clausePath", u.clausePath());
            m.put("section", u.section());
            m.put("current", u.unitId().equals(unitId));
            out.add(m);
        }
        if (out.stream().noneMatch(m -> Boolean.TRUE.equals(m.get("current")))) {
            return new ToolResult(false, "unit 不存在: " + unitId);
        }
        return new ToolResult(true, mapper.writeValueAsString(out));
    }
}

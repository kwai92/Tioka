package com.tioka.ontology.application.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code model.coverage} 工具（ONT-03，M3-03 §8）：主题覆盖四态统计（走 {@link CoverageService}）。
 * 只读，NONE 可逆性，动作类别 ontology.read。实例可见性先经 {@link ModelQueryService}
 * ACL 过滤（NOT_FOUND 口径一致）。dataLabel = 实例行 data_label（统计不含原始值，仍带实例密级）。
 */
public class ModelCoverageToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "model.coverage";

    private final ModelQueryService queries;
    private final CoverageService coverage;
    private final ObjectMapper mapper;
    private final EntityReadToolProvider.PrincipalAccessor principals;

    public ModelCoverageToolProvider(ModelQueryService queries,
                                     CoverageService coverage,
                                     ObjectMapper mapper,
                                     EntityReadToolProvider.PrincipalAccessor principals) {
        this.queries = queries;
        this.coverage = coverage;
        this.mapper = mapper;
        this.principals = principals;
    }

    @Override
    public String name() {
        return "ontology";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(ToolDescriptor.builder()
                .name(TOOL_NAME)
                .description("统计本体实例各主题覆盖四态（有 / 无 / 空白 / 不适用）并列出缺失期望项（只读）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "instanceKey", Map.of("type", "string"),
                                "version", Map.of("type", "string")),
                        "required", List.of("instanceKey")))
                .reversibility(Reversibility.NONE)
                .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        return new ToolResult(false, TOOL_NAME + " 须带 ToolCallContext 调用");
    }

    @Override
    public ToolResult invoke(ToolGateway.ToolCallContext ctx, String agentVersionId, String toolName,
                             Map<String, Object> arguments, Map<String, Object> agentToolConfig) {
        if (!TOOL_NAME.equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String instanceKey = EntityReadToolProvider.text(args.get("instanceKey"));
        if (instanceKey == null) {
            return new ToolResult(false, "instanceKey 必填");
        }
        try {
            Integer version = ModelQueryToolProvider.versionOf(args);
            Principal principal = principals.principal(ctx);
            OntInstanceVersionDO instance = queries.readConfirmed(instanceKey, version, principal).orElse(null);
            if (instance == null) {
                return new ToolResult(false, EntityReadToolProvider.NOT_FOUND);
            }
            List<CoverageService.TopicCoverage> topics = coverage.coverage(instance);
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (CoverageService.TopicCoverage t : topics) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("topic", t.topicKey());
                m.put("state", t.state().name());
                m.put("missingItems", t.missingItems());
                out.add(m);
            }
            String payload = mapper.writeValueAsString(Map.of(
                    "instanceKey", instanceKey,
                    "version", instance.getVersion(),
                    "topics", out));
            return new ToolResult(true, payload, instance.getDataLabel());
        } catch (NumberFormatException e) {
            return new ToolResult(false, "version 须为整数");
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }
}

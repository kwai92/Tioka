package com.tioka.ontology.application.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code model.query} 工具（ONT-03，M3-03 §8）：按模型键 / 主题查询 {@code ont_value_index}。
 * 只读，NONE 可逆性，动作类别 ontology.read。实例可见性先经 {@link ModelQueryService}
 * ACL 过滤（NOT_FOUND 口径一致）；再按 topic / status / 路径前缀过滤值索引行。
 * dataLabel = 实例行 data_label。
 */
public class ModelQueryToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "model.query";
    private static final int DEFAULT_LIMIT = 100;

    private final ModelQueryService queries;
    private final OntValueIndexRepository valueIndex;
    private final ObjectMapper mapper;
    private final EntityReadToolProvider.PrincipalAccessor principals;

    public ModelQueryToolProvider(ModelQueryService queries,
                                  OntValueIndexRepository valueIndex,
                                  ObjectMapper mapper,
                                  EntityReadToolProvider.PrincipalAccessor principals) {
        this.queries = queries;
        this.valueIndex = valueIndex;
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
                .description("按主题 / 状态 / 路径前缀查询本体实例的值索引（只读）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "instanceKey", Map.of("type", "string"),
                                "version", Map.of("type", "string"),
                                "topic", Map.of("type", "string"),
                                "status", Map.of("type", "string"),
                                "pathPrefix", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
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
        String topic = EntityReadToolProvider.text(args.get("topic"));
        String status = EntityReadToolProvider.text(args.get("status"));
        String pathPrefix = EntityReadToolProvider.text(args.get("pathPrefix"));
        int limit = args.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;

        try {
            Integer version = versionOf(args);
            Principal principal = principals.principal(ctx);
            OntInstanceVersionDO instance = queries.readConfirmed(instanceKey, version, principal).orElse(null);
            if (instance == null) {
                return new ToolResult(false, EntityReadToolProvider.NOT_FOUND);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (OntValueIndexDO row : valueIndex.list(instanceKey, instance.getVersion())) {
                if (topic != null && (row.getTopics() == null || !row.getTopics().contains(topic))) {
                    continue;
                }
                if (status != null && !status.equalsIgnoreCase(row.getStatus())) {
                    continue;
                }
                if (pathPrefix != null && (row.getPath() == null || !row.getPath().startsWith(pathPrefix))) {
                    continue;
                }
                if (rows.size() >= Math.max(1, limit)) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", row.getPath());
                m.put("status", row.getStatus());
                m.put("valueText", row.getValueText());
                m.put("topics", row.getTopics());
                rows.add(m);
            }
            String payload = mapper.writeValueAsString(Map.of(
                    "instanceKey", instanceKey,
                    "version", instance.getVersion(),
                    "rows", rows));
            return new ToolResult(true, payload, instance.getDataLabel());
        } catch (NumberFormatException e) {
            return new ToolResult(false, "version 须为整数");
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    static Integer versionOf(Map<String, Object> args) {
        String raw = EntityReadToolProvider.text(args.get("version"));
        return raw == null ? null : Integer.valueOf(raw);
    }
}

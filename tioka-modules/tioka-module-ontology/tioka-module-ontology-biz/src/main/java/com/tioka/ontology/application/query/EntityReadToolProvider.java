package com.tioka.ontology.application.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code entity.read} 工具（ONT-03，M3-03 §8）：按 instanceKey（+version，缺省最新确认版；
 * {@code version="draft"} 读草稿）读实例内容。只读，NONE 可逆性，动作类别 ontology.read。
 * 行过滤经 {@link ModelQueryService}（ACL 交集；无权限 / 不存在统一 NOT_FOUND，不泄露存在性）。
 * 不用 @Component：经 OntologyQueryAutoConfiguration 条件装配（tioka.ontology.query.enabled）。
 * dataLabel = 实例行 data_label（DAT-02 标签传播；草稿沿用 V172 缺省 CONFIDENTIAL）。
 */
public class EntityReadToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "entity.read";
    static final String NOT_FOUND = "NOT_FOUND";

    private final ModelQueryService queries;
    private final ObjectMapper mapper;
    private final PrincipalAccessor principals;

    /** 主体来源（ToolCallContext.principal 优先；缺省回退 PrincipalHolder，便于后台直调）。 */
    public interface PrincipalAccessor {
        Principal principal(ToolGateway.ToolCallContext ctx);
    }

    public EntityReadToolProvider(ModelQueryService queries, ObjectMapper mapper,
                                  ObjectProvider<PrincipalHolder> holders) {
        this(queries, mapper, ctx -> {
            if (ctx != null && ctx.principal() != null) {
                return ctx.principal();
            }
            PrincipalHolder holder = holders.getIfAvailable();
            return holder == null ? null : holder.find().orElse(null);
        });
    }

    public EntityReadToolProvider(ModelQueryService queries, ObjectMapper mapper, PrincipalAccessor principals) {
        this.queries = queries;
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
                .description("按 instanceKey 读取本体实例内容（缺省最新确认版，version=draft 读草稿；只读）")
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
        String instanceKey = text(args.get("instanceKey"));
        if (instanceKey == null) {
            return new ToolResult(false, "instanceKey 必填");
        }
        Principal principal = principals.principal(ctx);
        String rawVersion = text(args.get("version"));

        try {
            if ("draft".equalsIgnoreCase(rawVersion)) {
                return readDraft(instanceKey, principal);
            }
            Integer version = rawVersion == null ? null : Integer.valueOf(rawVersion);
            return queries.readConfirmed(instanceKey, version, principal)
                    .<ToolResult>map(this::toResult)
                    .orElseGet(() -> new ToolResult(false, NOT_FOUND));
        } catch (NumberFormatException e) {
            return new ToolResult(false, "version 须为整数或 draft");
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private ToolResult readDraft(String instanceKey, Principal principal) {
        return queries.readDraft(instanceKey, principal)
                .<ToolResult>map(draft -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("instanceKey", draft.getInstanceKey());
                    out.put("status", "DRAFT");
                    out.put("defKey", draft.getDefKey());
                    out.put("defVersion", draft.getDefVersion());
                    out.put("revision", draft.getRevision());
                    out.put("model", parse(draft.getModelJson()));
                    return new ToolResult(true, write(out), draftDataLabel());
                })
                .orElseGet(() -> new ToolResult(false, NOT_FOUND));
    }

    /** 草稿无独立列：沿确认投影 V172 缺省密级。 */
    static String draftDataLabel() {
        return "CONFIDENTIAL";
    }

    private ToolResult toResult(OntInstanceVersionDO row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceKey", row.getInstanceKey());
        out.put("version", row.getVersion());
        out.put("status", row.getStatus());
        out.put("defKey", row.getDefKey());
        out.put("defVersion", row.getDefVersion());
        out.put("contentHash", row.getContentHash());
        out.put("model", parse(row.getModelJson()));
        return new ToolResult(true, write(out), row.getDataLabel());
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }

    static String text(Object o) {
        String v = o == null ? null : String.valueOf(o);
        return v == null || v.isBlank() ? null : v;
    }
}

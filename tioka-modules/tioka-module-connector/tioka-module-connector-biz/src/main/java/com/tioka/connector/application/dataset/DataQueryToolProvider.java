package com.tioka.connector.application.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.DatasetResult;
import com.tioka.framework.error.BizException;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内置工具 {@code data.query}（DAT-01 / M3-02）：Agent 访问业务数据的唯一工具入口，
 * 调 {@link DatasetGateway}（ACL → 授予 → 策略 → 执行 → 掩码 → 打标签 → 审计）。
 * {@code ToolDescriptor.resourceArgs = {dataset: "dataset"}} 口径。
 *
 * <p>落位说明（偏差登记）：runtime-biz 禁止依赖 connector（ModuleDependencyArchTest），
 * 故本 Provider 落 connector-biz（同 DocToolProvider 在 ontology-biz 的先例），
 * 经 Spring 装配进 runtime ToolRegistry；principal.attrs 用户属性目录 EIS 对接后续批次，
 * M3 行谓词 attrs 用例经授予 scope 键（AND 合并）承载。
 */
@Component
public class DataQueryToolProvider implements ToolProvider {

    public static final String TOOL_NAME = "data.query";

    private final DatasetGateway gateway;
    private final ObjectProvider<GrantService> grants;
    private final ObjectMapper mapper;

    public DataQueryToolProvider(DatasetGateway gateway,
                                 ObjectProvider<GrantService> grants,
                                 ObjectMapper mapper) {
        this.gateway = gateway;
        this.grants = grants;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "dataset";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(ToolDescriptor.builder()
                .name(TOOL_NAME)
                .description("查询登记数据集：行级策略过滤 + 列掩码 + 结果打标（只读）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "dataset", Map.of("type", "string"),
                                "filter", Map.of("type", "string"),
                                "columns", Map.of("type", "array", "items", Map.of("type", "string")),
                                "limit", Map.of("type", "integer")),
                        "required", List.of("dataset")))
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
        if (ctx == null || ctx.principal() == null) {
            return new ToolResult(false, TOOL_NAME + " 缺少调用上下文");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String dataset = text(args.get("dataset"));
        if (dataset == null) {
            return new ToolResult(false, "dataset 必填");
        }
        String filter = text(args.get("filter"));
        List<String> columns = args.get("columns") instanceof List<?> l
                ? l.stream().filter(o -> o != null).map(String::valueOf).toList() : List.of();
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 100;

        // 授予：grantId → scope（AND 合并进行谓词）；null = 无授予链路（测试/根路径），scope 空
        Map<String, List<String>> scope = Map.of();
        if (ctx.grantId() != null && !ctx.grantId().isBlank()) {
            GrantService gs = grants.getIfAvailable();
            if (gs != null) {
                DelegationGrant g = gs.get(ctx.grantId());
                if (g != null && g.scope() != null) {
                    scope = g.scope().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                    e -> new ArrayList<>(e.getValue())));
                }
            }
        }
        DatasetPolicyModel.PrincipalSnapshot eff = new DatasetPolicyModel.PrincipalSnapshot(
                ctx.principal().userId(), List.copyOf(ctx.principal().roles()), Map.of());
        try {
            DatasetResult result = gateway.query(ctx.principal().workspaceId(), dataset,
                    new DatasetQuery(filter, columns, limit), eff, scope);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rows", result.rows());
            payload.put("label", result.label().name());
            payload.put("maskedColumns", result.maskedColumns());
            payload.put("postFiltered", result.postFiltered());
            return new ToolResult(true, mapper.writeValueAsString(payload), result.label().name());
        } catch (BizException e) {
            return new ToolResult(false, e.getMessage());
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private static String text(Object o) {
        String v = o == null ? null : String.valueOf(o);
        return v == null || v.isBlank() ? null : v;
    }
}

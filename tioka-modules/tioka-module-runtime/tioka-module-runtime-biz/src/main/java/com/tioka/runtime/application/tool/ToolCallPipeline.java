package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.runtime.api.spi.GrantCheck;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CON-01 第 1〜7 步授权管线；{@code call()} = authorize + 调用 + 审计（M1-00 F1）。
 */
@Component
public class ToolCallPipeline {
    private final ToolRegistry registry;
    private final GrantCheck grantCheck;
    private final ResourceGuard resourceGuard;
    private final PrincipalHolder principals;
    private final PrincipalLoaderApi principalLoader;
    private final ToolRpmLimiter rpmLimiter;
    private final ObjectProvider<EgressGuard> egressGuard;
    private final ObjectMapper mapper;

    public ToolCallPipeline(ToolRegistry registry,
                            GrantCheck grantCheck,
                            ResourceGuard resourceGuard,
                            PrincipalHolder principals,
                            PrincipalLoaderApi principalLoader,
                            ToolRpmLimiter rpmLimiter,
                            ObjectProvider<EgressGuard> egressGuard,
                            ObjectMapper mapper) {
        this.registry = registry;
        this.grantCheck = grantCheck;
        this.resourceGuard = resourceGuard;
        this.principals = principals;
        this.principalLoader = principalLoader;
        this.rpmLimiter = rpmLimiter;
        this.egressGuard = egressGuard;
        this.mapper = mapper;
    }

    public sealed interface AuthorizeResult {
        record Ok(AuthorizedCall call) implements AuthorizeResult {}
        /** step：拒绝发生在第几步；rateLimited 时由网关抛 {@code RATE_LIMITED}。 */
        record Reject(String reason, int step, boolean rateLimited) implements AuthorizeResult {}
    }

    public record AuthorizedCall(ToolProvider provider,
                                 ToolProvider.ToolDescriptor descriptor,
                                 Map<String, Object> args,
                                 Principal principal) {}

    public AuthorizeResult authorize(ToolGateway.ToolCallContext ctx, String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : new LinkedHashMap<>(args);
        if (ctx == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            return new AuthorizeResult.Reject("缺少 agentVersionId", 1, false);
        }
        String name = toolName == null ? "" : toolName;

        // 1. 解析工具（ENG-06：带 agentToolConfig，供 MCP 列举）
        Optional<ToolRegistry.ResolvedTool> resolved =
                registry.resolve(ctx.agentVersionId(), ctx.agentToolConfig(), name);
        if (resolved.isEmpty()) {
            return new AuthorizeResult.Reject("工具不存在", 1, false);
        }
        ToolRegistry.ResolvedTool rt = resolved.get();
        ToolProvider provider = rt.provider();
        ToolProvider.ToolDescriptor descriptor = rt.descriptor();

        // 2. 白名单
        List<String> allow = ctx.toolAllowList();
        if (allow != null && !allow.isEmpty() && !allow.contains(name)) {
            return new AuthorizeResult.Reject("不在 toolAllowList", 2, false);
        }

        // 2.5 授予
        String grantDeny = grantCheck.check(ctx, name, safeArgs);
        if (grantDeny != null && !grantDeny.isBlank()) {
            return new AuthorizeResult.Reject(grantDeny, 2, false);
        }

        // 3〜7：在 Principal 上下文中执行（ResourceGuard 读 PrincipalHolder）
        Principal principal = ctx.principal() != null ? ctx.principal() : principals.find().orElse(null);
        if (principal == null) {
            return new AuthorizeResult.Reject("无 Principal", 3, false);
        }
        Map<String, Object> argsForCall = safeArgs;
        return principals.runAs(principal, () -> {
            String authDeny = authorizePrincipal(ctx, provider, descriptor, name, argsForCall, principal);
            if (authDeny != null) {
                return new AuthorizeResult.Reject(authDeny, 3, false);
            }

            // 4. 权限重载（ADR-029 第 6 条：缓存 60s，由 PrincipalLoader 实现）
            Principal refreshed = reloadPrincipal(principal);
            return principals.runAs(refreshed, () -> {
                String deny = authorizePrincipal(ctx, provider, descriptor, name, argsForCall, refreshed);
                if (deny != null) {
                    return new AuthorizeResult.Reject(deny, 4, false);
                }

                // 5. 限流
                if (!rpmLimiter.tryAcquire(name, refreshed.workspaceId())) {
                    return new AuthorizeResult.Reject("tool_rpm 超限", 5, true);
                }

                Map<String, Object> filtered = argsForCall;
                if (isKbTool(descriptor) && hadKbIds(filtered)) {
                    filtered = filterKbArgs(descriptor, filtered);
                    if (kbIdsOf(filtered).isEmpty()) {
                        return new AuthorizeResult.Reject("知识库无权", 3, false);
                    }
                }

                // 6. 参数 schema
                String schemaDeny = validateSchema(descriptor, filtered);
                if (schemaDeny != null) {
                    return new AuthorizeResult.Reject(schemaDeny, 6, false);
                }

                // 7. 出网
                Optional<String> egress = provider.egressUrl(ctx.agentVersionId(), name, ctx.agentToolConfig());
                if (egress.isPresent()) {
                    EgressGuard guard = egressGuard.getIfAvailable();
                    if (guard != null) {
                        try {
                            guard.assertAllowed(egress.get());
                        } catch (BizException e) {
                            return new AuthorizeResult.Reject(
                                    e.getMessage() == null ? "出网拒绝" : e.getMessage(), 7, false);
                        }
                    }
                }

                return new AuthorizeResult.Ok(
                        new AuthorizedCall(provider, descriptor, filtered, refreshed));
            });
        });
    }

    private Principal reloadPrincipal(Principal current) {
        if (current == null || current.type() == PrincipalType.SYSTEM) {
            return current;
        }
        try {
            long uid = Long.parseLong(current.userId());
            Principal refreshed = principalLoader.load(uid, current.workspaceId());
            // 保持当前 runAs 栈，但用刷新后的权限集继续本调用
            return refreshed;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private String authorizePrincipal(ToolGateway.ToolCallContext ctx,
                                      ToolProvider provider,
                                      ToolProvider.ToolDescriptor descriptor,
                                      String name,
                                      Map<String, Object> args,
                                      Principal principal) {
        String providerName = provider.name() == null ? "" : provider.name();

        // MCP
        if ("mcp".equals(providerName)) {
            Optional<String> serverId = provider.mcpServerId(ctx.agentVersionId(), name, ctx.agentToolConfig());
            if (serverId.isEmpty()) {
                return "MCP 工具未绑定 server";
            }
            if (!mcpGrantAllows(ctx.agentToolConfig(), serverId.get(), name)) {
                return "无 MCP 授权";
            }
            if (!resourceGuard.allows("mcp", serverId.get(), AccessLevel.USE)) {
                return "无 MCP 资源权限";
            }
            return null;
        }

        // 知识库：逐个 kbId 过滤（过滤在 filterKbArgs；此处先校验 requiredPermission）
        if (isKbTool(descriptor)) {
            String req = descriptor.requiredPermission();
            if (req != null && !req.isBlank() && !principal.hasPermission(req)) {
                return "缺少权限 " + req;
            }
            List<String> ids = kbIdsOf(args);
            if (!ids.isEmpty()) {
                boolean any = false;
                for (String id : ids) {
                    if (resourceGuard.allows("kb", id, AccessLevel.USE)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return "知识库无权";
                }
            }
            return null;
        }

        // 内置及其他：requiredPermission
        String req = descriptor.requiredPermission();
        if (req != null && !req.isBlank() && !principal.hasPermission(req)) {
            return "缺少权限 " + req;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static boolean mcpGrantAllows(Map<String, Object> agentToolConfig, String serverId, String toolName) {
        if (agentToolConfig == null || agentToolConfig.isEmpty()) {
            return false;
        }
        Object raw = agentToolConfig.get("mcp");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object sid = m.get("serverId");
            if (sid == null || !serverId.equals(String.valueOf(sid))) {
                continue;
            }
            Object al = m.get("allowlist");
            if (al == null) {
                return true;
            }
            if (al instanceof List<?> allow) {
                if (allow.isEmpty()) {
                    return true;
                }
                for (Object t : allow) {
                    if (toolName.equals(String.valueOf(t))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Object> filterKbArgs(ToolProvider.ToolDescriptor descriptor,
                                             Map<String, Object> args) {
        if (!isKbTool(descriptor)) {
            return args;
        }
        List<String> ids = kbIdsOf(args);
        if (ids.isEmpty()) {
            return args;
        }
        List<String> allowed = new ArrayList<>();
        for (String id : ids) {
            if (resourceGuard.allows("kb", id, AccessLevel.USE)) {
                allowed.add(id);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(args);
        if (out.containsKey("kbIds")) {
            out.put("kbIds", allowed);
        }
        if (out.containsKey("kbId")) {
            if (allowed.isEmpty()) {
                out.remove("kbId");
            } else {
                out.put("kbId", allowed.get(0));
            }
        }
        return out;
    }

    private static boolean isKbTool(ToolProvider.ToolDescriptor d) {
        String n = d.name() == null ? "" : d.name();
        return n.startsWith("kb.") || "kb_search".equals(n);
    }

    @SuppressWarnings("unchecked")
    private static List<String> kbIdsOf(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Object multi = args.get("kbIds");
        if (multi instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        Object single = args.get("kbId");
        if (single != null && !String.valueOf(single).isBlank()) {
            String id = String.valueOf(single);
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static boolean hadKbIds(Map<String, Object> original) {
        if (original == null) {
            return false;
        }
        return original.containsKey("kbId") || original.containsKey("kbIds");
    }

    private String validateSchema(ToolProvider.ToolDescriptor descriptor, Map<String, Object> args) {
        if (descriptor.inputSchema() == null || descriptor.inputSchema().isEmpty()) {
            return null;
        }
        try {
            JsonNode schemaNode = mapper.valueToTree(descriptor.inputSchema());
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            JsonNode data = mapper.valueToTree(args == null ? Map.of() : args);
            Set<ValidationMessage> errors = schema.validate(data);
            if (errors == null || errors.isEmpty()) {
                return null;
            }
            return "参数不合 schema: " + errors.iterator().next().getMessage();
        } catch (RuntimeException e) {
            return "参数校验失败: " + e.getMessage();
        }
    }
}

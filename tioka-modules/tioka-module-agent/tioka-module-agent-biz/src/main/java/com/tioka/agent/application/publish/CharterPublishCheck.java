package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.agent.api.publish.BuiltinToolCatalog;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.authz.ResourceAclApi;
import com.tioka.system.api.authz.SystemRoles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TRU-01：AgentSpec {@code charter} 段发布校验（特性 {@code tioka.trust.charter.enabled}）。
 * <ul>
 *   <li>非 READ 写工具必须落入某个动作类别的 toolPatterns</li>
 *   <li>{@code actingAs=SERVICE} 时发布者对 serviceAccountId 有 USE</li>
 *   <li>动作类别 ceiling &gt; L2 时发布者须为空间管理员</li>
 *   <li>MAS-02：delegable ⊆ 本授权书动作类别（C-20：subAgents 解冻后仅含 agentId 白名单，
 *       成员授权书类别并集无法在发布期反查，归 M4 SubAgentPolicy 扩展）</li>
 * </ul>
 */
@Component
@Order(26)
public class CharterPublishCheck implements PublishCheck {

    static final String RESOURCE_SERVICE_ACCOUNT = "service_account";
    static final String CODE_UNCLASSIFIED = "CHARTER_TOOL_UNCLASSIFIED";
    static final String CODE_SA_FORBIDDEN = "CHARTER_SERVICE_ACCOUNT_FORBIDDEN";
    static final String CODE_CEILING_ADMIN = "CHARTER_CEILING_REQUIRES_ADMIN";
    static final String CODE_DELEGABLE_INVALID = "CHARTER_DELEGABLE_INVALID";
    static final String CODE_INVALID = "CHARTER_INVALID";

    /** L2 及以下可由普通发布者发布；高于此须空间管理员（M3 用角色代替 GOV-06）。 */
    private static final int MAX_NON_ADMIN_CEILING = 2;

    private final boolean enabled;
    private final PrincipalHolder principals;
    private final ResourceAclApi resourceAcl;
    private final ObjectProvider<BuiltinToolCatalog> builtins;

    public CharterPublishCheck(
            @Value("${tioka.trust.charter.enabled:false}") boolean enabled,
            PrincipalHolder principals,
            ResourceAclApi resourceAcl,
            ObjectProvider<BuiltinToolCatalog> builtins) {
        this.enabled = enabled;
        this.principals = principals;
        this.resourceAcl = resourceAcl;
        this.builtins = builtins;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        if (!enabled || ctx.rollback()) {
            return List.of();
        }
        JsonNode charter = spec.charter();
        if (charter == null || charter.isNull() || charter.isMissingNode()) {
            return List.of();
        }
        if (!charter.isObject()) {
            return List.of(new PublishError("/charter", CODE_INVALID, "charter 必须是对象"));
        }

        List<PublishError> errors = new ArrayList<>();
        checkWriteToolsClassified(spec, charter, errors);
        checkServiceAccountUse(charter, errors);
        checkCeilingRequiresAdmin(charter, errors);
        checkDelegableScope(spec, charter, errors);
        return errors;
    }

    private void checkWriteToolsClassified(AgentSpec spec, JsonNode charter, List<PublishError> errors) {
        Set<String> writeTools = collectNonReadTools(spec);
        if (writeTools.isEmpty()) {
            return;
        }
        List<Pattern> patterns = new ArrayList<>();
        JsonNode classes = charter.get("actionClasses");
        if (classes != null && classes.isArray()) {
            for (JsonNode ac : classes) {
                JsonNode pats = ac.get("toolPatterns");
                if (pats == null || !pats.isArray()) {
                    continue;
                }
                for (JsonNode p : pats) {
                    if (p != null && p.isTextual() && !p.asText().isBlank()) {
                        patterns.add(globToPattern(p.asText().trim()));
                    }
                }
            }
        }
        for (String tool : writeTools) {
            boolean matched = false;
            for (Pattern pat : patterns) {
                if (pat.matcher(tool).matches()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(new PublishError(
                        "/charter/actionClasses",
                        CODE_UNCLASSIFIED,
                        "非 READ 工具未归入任何动作类别: " + tool));
            }
        }
    }

    private void checkServiceAccountUse(JsonNode charter, List<PublishError> errors) {
        String actingAs = text(charter, "actingAs");
        if (actingAs == null || !"SERVICE".equalsIgnoreCase(actingAs)) {
            return;
        }
        String saId = text(charter, "serviceAccountId");
        if (saId == null || saId.isBlank()) {
            errors.add(new PublishError(
                    "/charter/serviceAccountId",
                    CODE_INVALID,
                    "actingAs=SERVICE 时 serviceAccountId 必填"));
            return;
        }
        if (!resourceAcl.aclAllows(RESOURCE_SERVICE_ACCOUNT, saId, AccessLevel.USE)) {
            errors.add(new PublishError(
                    "/charter/serviceAccountId",
                    CODE_SA_FORBIDDEN,
                    "发布者对服务账号无 USE: " + saId));
        }
    }

    private void checkCeilingRequiresAdmin(JsonNode charter, List<PublishError> errors) {
        JsonNode classes = charter.get("actionClasses");
        if (classes == null || !classes.isArray()) {
            return;
        }
        Principal publisher = principals.current();
        boolean admin = isWorkspaceAdmin(publisher);
        for (int i = 0; i < classes.size(); i++) {
            JsonNode ac = classes.get(i);
            String ceiling = text(ac, "ceiling");
            if (ceiling == null || ceiling.isBlank()) {
                continue;
            }
            int level = parseAutonomyLevel(ceiling);
            if (level > MAX_NON_ADMIN_CEILING && !admin) {
                errors.add(new PublishError(
                        "/charter/actionClasses/" + i + "/ceiling",
                        CODE_CEILING_ADMIN,
                        "动作类别等级上限高于 L2 须由空间管理员发布: " + ceiling));
            }
        }
    }

    /**
     * MAS-02：可下放类别 ⊆ 本授权书动作类别。
     * 顶层 {@code delegable} 数组优先；缺省按 {@code actionClasses[].delegable=true} 派生（C-2b 兼容口径）。
     * C-20 裁决：subAgents 权威形态为 {@code {"allowed":[agentId...]}}，只含 agentId、不含成员授权书，
     * 且发布期反查会与父子发布顺序形成死锁，故 C-2c 的「成员类别并集（尽力解析）」正式作废；
     * 待 M4 SubAgentPolicy 引入成员授权书快照后再启用。
     */
    private void checkDelegableScope(AgentSpec spec, JsonNode charter, List<PublishError> errors) {
        List<String> requested = requestedDelegable(charter);
        if (requested.isEmpty()) {
            return;
        }
        Set<String> allowed = allowedDelegableScope(charter);
        List<String> violations = new ArrayList<>();
        for (String c : requested) {
            if (!allowed.contains(c)) {
                violations.add(c);
            }
        }
        if (!violations.isEmpty()) {
            errors.add(new PublishError(
                    "/charter/delegable",
                    CODE_DELEGABLE_INVALID,
                    "delegable 含越界动作类别（须为本授权书动作类别）: "
                            + String.join(", ", violations)));
        }
    }

    /** 待校验的可下放类别：顶层 delegable 数组优先，缺省按 delegable=true 标志派生。 */
    private static List<String> requestedDelegable(JsonNode charter) {
        JsonNode delegable = charter.get("delegable");
        if (delegable != null && delegable.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode v : delegable) {
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    out.add(v.asText().trim());
                }
            }
            return out;
        }
        List<String> out = new ArrayList<>();
        JsonNode classes = charter.get("actionClasses");
        if (classes != null && classes.isArray()) {
            for (JsonNode ac : classes) {
                if (ac.path("delegable").asBoolean(false)) {
                    String code = text(ac, "code");
                    if (code != null && !code.isBlank()) {
                        out.add(code);
                    }
                }
            }
        }
        return out;
    }

    /** allowed = 本授权书 actionClasses codes（C-20：成员类别并集作废，见 checkDelegableScope）。 */
    private static Set<String> allowedDelegableScope(JsonNode charter) {
        Set<String> allowed = new HashSet<>();
        JsonNode classes = charter.get("actionClasses");
        if (classes != null && classes.isArray()) {
            for (JsonNode ac : classes) {
                String code = text(ac, "code");
                if (code != null && !code.isBlank()) {
                    allowed.add(code);
                }
            }
        }
        return allowed;
    }

    /**
     * MCP allowlist 工具默认按 WRITE（mcp_tool 缺省）；builtin 目录中的工具按 READ（可不归类）。
     */
    private Set<String> collectNonReadTools(AgentSpec spec) {
        Set<String> write = new HashSet<>();
        if (spec.tools() == null) {
            return write;
        }
        BuiltinToolCatalog catalog = builtins.getIfAvailable();
        for (String name : spec.tools().builtin()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            // 未在 builtin 目录中的名称仍可能是写工具；目录命中视为 READ
            if (catalog == null || !catalog.contains(name)) {
                write.add(name);
            }
        }
        for (AgentSpec.ToolSet.McpBinding b : spec.tools().mcp()) {
            for (String name : b.allowlist()) {
                if (name != null && !name.isBlank()) {
                    write.add(name);
                }
            }
        }
        return write;
    }

    private static boolean isWorkspaceAdmin(Principal p) {
        if (p == null) {
            return false;
        }
        if (p.hasPermission("*") || p.hasPermission("tenant:admin")) {
            return true;
        }
        Set<String> roles = p.roles();
        return roles != null && (roles.contains(SystemRoles.SYSTEM_ADMIN)
                || roles.contains(SystemRoles.WORKSPACE_ADMIN));
    }

    private static int parseAutonomyLevel(String raw) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("L") && s.length() >= 2) {
            try {
                return Integer.parseInt(s.substring(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v.isTextual() ? v.asText() : v.asText(null);
    }
}

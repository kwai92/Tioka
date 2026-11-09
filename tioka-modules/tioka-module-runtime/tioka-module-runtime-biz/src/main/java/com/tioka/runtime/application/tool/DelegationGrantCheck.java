package com.tioka.runtime.application.tool;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.GrantCheck;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具调用管线第 2.5 步「授予」真实现（ADR-039 / M3-01 MAS-01），替换 M1 恒放行占位。
 *
 * <p>{@code ctx.grantId == null} 视为根授予尚未签发（仅测试 / 旧链路），放行。
 * 拒绝返回非空原因，拒绝步号记 step=2，统一 {@code ToolResult(false,...)} 不终止 Turn。
 *
 * <p>TRU-02 动作类别回填（M3-05 §3.2）：risk != READ 的工具动作类别须 ∈ grant.actionClasses，
 * 类别来源为类型化 Charter（agent-api {@link Charter}，经快照解析）+ 工具注册表 risk 元数据；
 * Agent 无授权书（存量链路）保持放行兼容并登记批次文档。
 *
 * <p>TODO（登记偏差）：descriptor 无 resourceArgs 元数据，mcp/flow/dataset 资源 ID 归属无法识别，
 * 仅 kb（kbId/kbIds 参数键，M1-04 口径）做交集剔除。
 */
@Component
public class DelegationGrantCheck implements GrantCheck {

    private static final Logger log = LoggerFactory.getLogger(DelegationGrantCheck.class);

    private final GrantService grants;
    private final ToolRegistry registry;
    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;

    public DelegationGrantCheck(GrantService grants,
                                ToolRegistry registry,
                                ObjectProvider<AgentSnapshotApi> agentSnapshots) {
        this.grants = grants;
        this.registry = registry;
        this.agentSnapshots = agentSnapshots;
    }

    @Override
    public String check(ToolGateway.ToolCallContext ctx, String toolName, Map<String, Object> args) {
        if (ctx == null || ctx.grantId() == null || ctx.grantId().isBlank()) {
            return null;
        }
        DelegationGrant grant = grants.get(ctx.grantId());
        if (grant == null || grant.status() != GrantStatus.ACTIVE
                || (grant.expiresAt() != null && Instant.now().isAfter(grant.expiresAt()))) {
            return "GRANT_INACTIVE: 委派授予已失效";
        }
        if (toolName == null || !grant.tools().contains(toolName)) {
            return "GRANT_DENIED: 不在委派授予内";
        }

        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        // kb：按交集剔除（M1-04 口径），剔除后为空 → DENIED
        Set<String> kbAllowed = grant.resources() == null ? Set.of()
                : grant.resources().getOrDefault("kb", Set.of());
        if (!kbAllowed.isEmpty() && (safeArgs.containsKey("kbId") || safeArgs.containsKey("kbIds"))) {
            List<String> kept = intersectKb(safeArgs, kbAllowed);
            if (kept.isEmpty()) {
                return "GRANT_DENIED: 知识库不在授予内";
            }
            if (args != null) {
                applyKbFilter(args, kept);
            }
        }

        // scope：参数取值越界 → DENIED
        if (grant.scope() != null) {
            for (Map.Entry<String, List<String>> e : grant.scope().entrySet()) {
                Object v = safeArgs.get(e.getKey());
                if (v == null) {
                    continue;
                }
                if (!e.getValue().contains(String.valueOf(v))) {
                    return "GRANT_DENIED: 超出任务范围 " + e.getKey();
                }
            }
        }

        // TRU-02 动作类别：risk != READ 须归类且 ∈ grant.actionClasses（有授权书才收紧）
        String classDeny = checkActionClass(ctx, grant, toolName);
        if (classDeny != null) {
            return classDeny;
        }
        return null;
    }

    private String checkActionClass(ToolGateway.ToolCallContext ctx, DelegationGrant grant,
                                    String toolName) {
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (snapshots == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            return null;
        }
        Charter charter;
        try {
            AgentSpec spec = snapshots.get(ctx.agentVersionId()).spec();
            charter = spec == null ? null : Charter.parse(spec.charter());
        } catch (RuntimeException e) {
            log.debug("动作类别检查快照加载失败 version={}: {}", ctx.agentVersionId(), e.toString());
            return null;
        }
        if (charter == null || charter.actionClasses().isEmpty()) {
            // 无授权书（存量链路 / TRU-01 关闭）：保持放行兼容
            return null;
        }
        ToolRisk risk = riskOf(ctx, toolName);
        if (risk == ToolRisk.READ) {
            return null;
        }
        Charter.ActionClass classified = charter.classify(toolName);
        if (classified == null) {
            return "GRANT_DENIED: 工具未归入任何动作类别: " + toolName;
        }
        Set<String> allowed = grant.actionClasses() == null ? Set.of() : grant.actionClasses();
        if (!allowed.contains(classified.code())) {
            return "GRANT_DENIED: 动作类别不在授予内 " + classified.code();
        }
        return null;
    }

    private ToolRisk riskOf(ToolGateway.ToolCallContext ctx, String toolName) {
        try {
            Optional<ToolRegistry.ResolvedTool> resolved = registry.resolve(ctx.agentVersionId(),
                    ctx.agentToolConfig(), toolName);
            if (resolved.isPresent() && resolved.get().descriptor().risk() != null) {
                return resolved.get().descriptor().risk();
            }
        } catch (RuntimeException ignored) {
            // 解析失败按 WRITE（更严）处理
        }
        return ToolRisk.WRITE;
    }

    private List<String> intersectKb(Map<String, Object> args, Set<String> kbAllowed) {
        List<String> out = new ArrayList<>();
        Object multi = args.get("kbIds");
        if (multi instanceof List<?> list) {
            for (Object o : list) {
                String id = String.valueOf(o);
                if (kbAllowed.contains(id) && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        Object single = args.get("kbId");
        if (single != null) {
            String id = String.valueOf(single);
            if (kbAllowed.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyKbFilter(Map<String, Object> args, List<String> kept) {
        Map<String, Object> mutable = args;
        if (mutable.containsKey("kbIds")) {
            mutable.put("kbIds", new ArrayList<>(kept));
        }
        if (mutable.containsKey("kbId")) {
            if (kept.isEmpty()) {
                mutable.remove("kbId");
            } else {
                mutable.put("kbId", kept.get(0));
            }
        }
    }
}

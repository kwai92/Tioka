package com.tioka.runtime.application.pipeline;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyContext;
import com.tioka.governance.api.autonomy.AutonomyDecision;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.runtime.api.approval.AgentApprovalSettingsLookup;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineOrders;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * 工具管线第 10 步「自治等级判定」（TRU-09，ADR-034 判定表）。
 *
 * <ul>
 *   <li>等级：{@code AutonomyApi.levelOf(agentId, actionClass)}，无记录 = L1（初始等级）；
 *       授权书上限 {@code ceiling} 钳制（缺省 L2）</li>
 *   <li>观察期：Agent 版本发布后 7 天内 ≥L3 按 L2（M3-05 §7；EVO-06 门禁 M4 不提前结束）</li>
 *   <li>toolPolicies 只能收紧：显式 {@code approve}/{@code deny} 照办；{@code auto} 不越过等级</li>
 *   <li>判定结果写 context（{@code autonomy.decision}/{@code autonomy.level}），由
 *       {@code DefaultToolGateway#prepare} 据此落 gate；本步缺失（特性关闭）时网关退回
 *       agent-config 审批策略（降级开关 {@code tioka.trust.autonomy.enabled}，登记口径）</li>
 *   <li>DENIED → FORBIDDEN 拒绝；SHADOW_ONLY → M3 无影子 Run，按 ADR-033 口径落
 *       SHADOW_ONLY gate（执行环不自动提交，登记简化）</li>
 *   <li>TAINTED / injectionSensitive 输入 M3 恒为 false（护栏 GRD 在 M4，字段先行）</li>
 * </ul>
 */
@Component
public class AutonomyGatePipelineStep implements ToolPipelineStep {

    private static final Logger log = LoggerFactory.getLogger(AutonomyGatePipelineStep.class);

    /** 观察期：新版本发布后 7 天内 L3/L4 按 L2（M3-05 §7）。 */
    static final Duration OBSERVATION_PERIOD = Duration.ofDays(7);

    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;
    private final ObjectProvider<AgentApprovalSettingsLookup> toolPolicies;
    private final ObjectProvider<AutonomyGate> gate;
    private final ObjectProvider<AutonomyApi> autonomyApi;

    public AutonomyGatePipelineStep(ObjectProvider<AgentSnapshotApi> agentSnapshots,
                                    ObjectProvider<AgentApprovalSettingsLookup> toolPolicies,
                                    ObjectProvider<AutonomyGate> gate,
                                    ObjectProvider<AutonomyApi> autonomyApi,
                                    @Value("${tioka.trust.autonomy.enabled:true}") boolean enabled) {
        this.agentSnapshots = agentSnapshots;
        this.toolPolicies = toolPolicies;
        this.gate = gate;
        this.autonomyApi = autonomyApi;
        this.enabled = enabled;
    }

    private final boolean enabled;

    @Override
    public double order() {
        return ToolPipelineOrders.AUTONOMY_GATE;
    }

    @Override
    public String name() {
        return "autonomy-gate";
    }

    @Override
    public ToolPipelineVerdict execute(ToolPipelineContext ctx) {
        if (!enabled) {
            // 特性关闭：跳过判定，网关退回 agent-config（切换口径见 M3-C4 设计笔记）
            return ToolPipelineVerdict.CONTINUE;
        }
        AutonomyGate g = gate.getIfAvailable();
        if (g == null) {
            return ToolPipelineVerdict.CONTINUE;
        }
        ToolGateway.ToolCallContext call = ctx.callContext();
        SnapshotView snapshot = loadSnapshot(call == null ? null : call.agentVersionId());
        Charter.ActionClass actionClass = snapshot.charter() == null
                ? null : snapshot.charter().classify(ctx.toolName());

        int level = resolveLevel(snapshot, actionClass);
        boolean observation = inObservationPeriod(snapshot.publishedAt());
        AutonomyContext context = new AutonomyContext(
                cedarOf(ctx),
                call == null ? "" : call.origin(),
                level,
                ctx.get("pipeline.reversibility", String.class) == null
                        ? "IRREVERSIBLE" : ctx.get("pipeline.reversibility", String.class),
                Boolean.TRUE.equals(ctx.get("pipeline.estimated", Boolean.class)),
                riskOf(ctx).name(),
                observation,
                explicitToolPolicy(call, ctx.toolName()),
                snapshot.agentId(),
                call == null ? null : call.agentVersionId(),
                actionClass == null ? "" : actionClass.code(),
                false,
                false);

        AutonomyDecision decision;
        try {
            decision = g.decide(context);
        } catch (RuntimeException e) {
            // 判定异常宁可多审：按 REQUIRE_APPROVAL 兜底（不阻断预演主链）
            log.warn("autonomy gate 判定异常，降级为审批: {}", e.toString());
            decision = AutonomyDecision.REQUIRE_APPROVAL;
        }

        ctx.put("autonomy.decision", decision.name());
        ctx.put("autonomy.level", level);
        if (decision == AutonomyDecision.DENIED) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "自治判定拒绝: " + (snapshot.agentId() == null ? "" : snapshot.agentId())
                            + "/" + (actionClass == null ? "" : actionClass.code()));
        }
        return ToolPipelineVerdict.CONTINUE;
    }

    /** 等级 = 存储等级（缺省 L1），按授权书 ceiling 钳制（缺省 L2）。 */
    private int resolveLevel(SnapshotView snapshot, Charter.ActionClass actionClass) {
        int level = 1;
        AutonomyApi api = autonomyApi.getIfAvailable();
        if (api != null && snapshot.agentId() != null) {
            try {
                level = api.levelOf(snapshot.agentId(),
                        actionClass == null ? "" : actionClass.code());
            } catch (RuntimeException e) {
                log.debug("levelOf 失败，按 L1: {}", e.toString());
            }
        }
        int ceiling = 2;
        if (actionClass != null && actionClass.ceiling() != null && !actionClass.ceiling().isBlank()) {
            try {
                ceiling = Integer.parseInt(actionClass.ceiling().trim().replace("L", ""));
            } catch (NumberFormatException e) {
                ceiling = 2;
            }
        }
        return Math.min(level, ceiling);
    }

    private boolean inObservationPeriod(Instant publishedAt) {
        return publishedAt != null && publishedAt.plus(OBSERVATION_PERIOD).isAfter(Instant.now());
    }

    /** 仅显式配置收紧（{@code auto} 不越过等级 → 返回 auto 由判定表忽略）。 */
    private String explicitToolPolicy(ToolGateway.ToolCallContext call, String toolName) {
        AgentApprovalSettingsLookup lookup = toolPolicies.getIfAvailable();
        if (lookup == null || call == null || call.agentVersionId() == null) {
            return null;
        }
        try {
            return lookup.toolPolicies(call.agentVersionId())
                    .orElse(Map.of())
                    .get(toolName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PolicyDecision cedarOf(ToolPipelineContext ctx) {
        String decision = ctx.get("policy.decision", String.class);
        if (decision == null) {
            return PolicyDecision.ALLOW;
        }
        try {
            return PolicyDecision.valueOf(decision.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PolicyDecision.ALLOW;
        }
    }

    private ToolRisk riskOf(ToolPipelineContext ctx) {
        String risk = ctx.get("pipeline.risk", String.class);
        if (risk != null) {
            try {
                return ToolRisk.valueOf(risk);
            } catch (IllegalArgumentException ignored) {
                // fallthrough
            }
        }
        return ToolRisk.WRITE;
    }

    private record SnapshotView(String agentId, Charter charter, java.time.Instant publishedAt) {}

    private SnapshotView loadSnapshot(String agentVersionId) {
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (snapshots == null || agentVersionId == null || agentVersionId.isBlank()) {
            return new SnapshotView(null, null, null);
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = snapshots.get(agentVersionId);
            AgentSpec spec = snap.spec();
            Charter charter;
            try {
                charter = spec == null ? null : Charter.parse(spec.charter());
            } catch (IllegalArgumentException e) {
                charter = null;
            }
            return new SnapshotView(snap.agentId(), charter, snap.publishedAt());
        } catch (RuntimeException e) {
            log.debug("autonomy step 快照加载失败 version={}: {}", agentVersionId, e.toString());
            return new SnapshotView(null, null, null);
        }
    }
}

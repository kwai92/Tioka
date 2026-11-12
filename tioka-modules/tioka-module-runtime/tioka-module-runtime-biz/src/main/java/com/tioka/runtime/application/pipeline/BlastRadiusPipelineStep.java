package com.tioka.runtime.application.pipeline;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineOrders;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.application.tool.RunBlastRadiusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具管线第 11 步「爆炸半径」（TRU-08 / M3-05 §6）。
 *
 * <ul>
 *   <li>按（Turn、动作类别）在 Redis 累计变更单的 records 与 amountMicros，键 2h 过期</li>
 *   <li>加上本次后超过授权书 {@code perRunRecords}，或本次超过 {@code perCallAmountMicros}
 *       → 返回 ESCALATE，由 {@code DefaultToolGateway} 把判定至少提到 REQUIRE_APPROVAL</li>
 *   <li>授权书未配置这两项限额时跳过（不改变既有行为）；Redis 不可用时 unknown=true，
 *       按「宁可多转审批」同样 ESCALATE，与 {@code DailyCounterStore} 口径一致</li>
 *   <li>判定在 prepare 阶段累计，审批拒绝后累计值偏高（保守方向，登记偏离）</li>
 * </ul>
 */
@Component
public class BlastRadiusPipelineStep implements ToolPipelineStep {

    private static final Logger log = LoggerFactory.getLogger(BlastRadiusPipelineStep.class);

    private final ObjectProvider<AgentSnapshotApi> snapshots;
    private final ObjectProvider<RunBlastRadiusStore> store;
    private final boolean enabled;

    public BlastRadiusPipelineStep(ObjectProvider<AgentSnapshotApi> snapshots,
                                   ObjectProvider<RunBlastRadiusStore> store,
                                   @Value("${tioka.trust.blast-radius.enabled:true}") boolean enabled) {
        this.snapshots = snapshots;
        this.store = store;
        this.enabled = enabled;
    }

    @Override
    public double order() {
        return ToolPipelineOrders.BLAST_RADIUS;
    }

    @Override
    public String name() {
        return "blast-radius";
    }

    @Override
    public ToolPipelineVerdict execute(ToolPipelineContext ctx) {
        if (!enabled) {
            return ToolPipelineVerdict.CONTINUE;
        }
        ToolGateway.ToolCallContext call = ctx.callContext();
        if (call == null || call.turnId() == null || call.turnId().isBlank()) {
            return ToolPipelineVerdict.CONTINUE;
        }
        Charter.ActionClass actionClass = classify(call.agentVersionId(), ctx.toolName());
        if (actionClass == null || actionClass.limits() == null) {
            return ToolPipelineVerdict.CONTINUE;
        }
        Charter.Limits limits = actionClass.limits();
        Integer perRunRecords = limits.perRunRecords();
        Long perCallAmount = limits.perCallAmountMicros();
        if (perRunRecords == null && perCallAmount == null) {
            return ToolPipelineVerdict.CONTINUE;
        }

        long records = longAttr(ctx, "changeset.records");
        long amount = longAttr(ctx, "changeset.amountMicros");
        RunBlastRadiusStore counters = store.getIfAvailable();
        if (counters == null) {
            return ToolPipelineVerdict.CONTINUE;
        }
        RunBlastRadiusStore.Usage usage = counters.add(call.turnId(), actionClass.code(), amount, records);
        ctx.put("blastRadius.scopeId", call.turnId());
        ctx.put("blastRadius.actionClass", actionClass.code());
        ctx.put("blastRadius.records", usage.records());
        ctx.put("blastRadius.amountMicros", usage.amountMicros());

        if (!exceeded(usage, perRunRecords, perCallAmount, amount)) {
            return ToolPipelineVerdict.CONTINUE;
        }
        ctx.put("blastRadius.exceeded", Boolean.TRUE);
        // 审批卡片带动作类别，与第 9 步 ESCALATE 口径一致
        ctx.put("policy.actionClass", actionClass.code());
        ctx.put("policy.fourEyes", Boolean.FALSE);
        log.warn("单 Run 爆炸半径超限转审批: turn={} class={} records={} amount={}",
                call.turnId(), actionClass.code(), usage.records(), usage.amountMicros());
        return ToolPipelineVerdict.ESCALATE;
    }

    /** 纯判定（离线单测可直接覆盖）：累计 records 超 perRunRecords，或本次金额超单次上限。 */
    static boolean exceeded(RunBlastRadiusStore.Usage usage, Integer perRunRecords,
                            Long perCallAmount, long currentAmount) {
        if (usage == null) {
            return false;
        }
        return usage.unknown()
                || (perRunRecords != null && usage.records() > perRunRecords)
                || (perCallAmount != null && currentAmount > perCallAmount);
    }

    private static long longAttr(ToolPipelineContext ctx, String key) {
        Object v = ctx.attributesView().get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private Charter.ActionClass classify(String agentVersionId, String toolName) {
        AgentSnapshotApi api = snapshots.getIfAvailable();
        if (api == null || agentVersionId == null || agentVersionId.isBlank()) {
            return null;
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = api.get(agentVersionId);
            AgentSpec spec = snap == null ? null : snap.spec();
            Charter charter = spec == null ? null : Charter.parse(spec.charter());
            return charter == null ? null : charter.classify(toolName);
        } catch (RuntimeException e) {
            log.debug("爆炸半径步骤授权书加载失败 version={}: {}", agentVersionId, e.toString());
            return null;
        }
    }
}

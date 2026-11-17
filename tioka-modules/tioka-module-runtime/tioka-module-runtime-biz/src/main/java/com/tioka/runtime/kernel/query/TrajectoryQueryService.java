package com.tioka.runtime.kernel.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.CostQueryApi;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.ThreadLabelApi;
import com.tioka.runtime.api.kernel.TrajectoryApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.config.ObservabilityProperties;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.authz.SystemRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * HCI-07b：轨迹按步查询（含作废 item 与按步用量）。
 *
 * <p>轨迹正文门控（ADR-072 §6 / ADR-074 / FE-00 §7.10）：条目本体不因标签而隐藏，但
 * {@code runtime_item.data_label} 序高于调用者 {@code clearance} 时抹除正文（只留结构）；
 * 调用者持 {@code trajectory:read:content} 或为平台级管理员时 {@code clearance = SECRET}，
 * 否则上限为 {@code INTERNAL}。实际读到受门控正文时写一条分类 {@code DATA} 的审计。
 */
@Service
public class TrajectoryQueryService implements TrajectoryApi, ThreadLabelApi {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryQueryService.class);
    /** 轨迹正文查看审计动作（ADR-072 §6；登记于 docs/contracts/audit/actions.yaml）。 */
    static final String ACTION_READ_CONTENT = "trajectory.content.read";

    private final TurnRepository turns;
    private final ItemRepository items;
    private final RuntimeThreadRepository threads;
    private final CostQueryApi costs;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final ObservabilityProperties observability;
    private final ObjectProvider<AuditApi> auditApi;
    private final ObjectProvider<AuditDegradedSink> degradedSink;

    public TrajectoryQueryService(TurnRepository turns,
                                  ItemRepository items,
                                  RuntimeThreadRepository threads,
                                  CostQueryApi costs,
                                  PrincipalHolder principals,
                                  ObjectMapper mapper,
                                  ObservabilityProperties observability,
                                  ObjectProvider<AuditApi> auditApi,
                                  ObjectProvider<AuditDegradedSink> degradedSink) {
        this.turns = turns;
        this.items = items;
        this.threads = threads;
        this.costs = costs;
        this.principals = principals;
        this.mapper = mapper;
        this.observability = observability;
        this.auditApi = auditApi;
        this.degradedSink = degradedSink;
    }

    @Override
    public Trajectory turn(String turnId) {
        RuntimeTurnDO t = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        requireThreadVisible(t.getThreadId());
        guardOrchRead(t.getOrigin());

        DataLabel clearance = clearance(principals.current());
        int itemCount = 0;
        boolean gatedContentRevealed = false;
        Map<Integer, List<RuntimeApi.ItemView>> byStep = new TreeMap<>();
        for (RuntimeItemDO item : items.listByTurn(turnId)) {
            itemCount++;
            DataLabel own = parseLabel(item.getDataLabel());
            boolean gated = own != null && own.ordinal() > DataLabel.INTERNAL.ordinal();
            boolean redacted = gated && !own.atMost(clearance);
            if (gated && !redacted) {
                gatedContentRevealed = true;
            }
            int step = item.getStep() == null ? 0 : item.getStep();
            byStep.computeIfAbsent(step, k -> new ArrayList<>()).add(toItemView(item, redacted));
        }

        Map<Integer, CostQueryApi.UsageEntry> usageByStep = new LinkedHashMap<>();
        for (CostQueryApi.UsageEntry u : costs.turnUsage(turnId)) {
            if (u.step() == null) {
                continue;
            }
            usageByStep.merge(u.step(), u, TrajectoryQueryService::mergeUsage);
        }

        List<Step> steps = new ArrayList<>();
        for (Map.Entry<Integer, List<RuntimeApi.ItemView>> e : byStep.entrySet()) {
            CostQueryApi.UsageEntry u = usageByStep.get(e.getKey());
            boolean failover = u != null && u.status() != null
                    && u.status().toUpperCase().contains("FAILOVER");
            steps.add(new Step(
                    e.getKey(),
                    e.getValue(),
                    u == null ? null : u.modelRef(),
                    u == null ? null : u.promptTokens(),
                    u == null ? null : u.completionTokens(),
                    u == null || u.latencyMs() == null ? null : u.latencyMs().longValue(),
                    u == null ? null : u.costMicros(),
                    failover));
        }
        if (gatedContentRevealed) {
            auditContentView(t, itemCount);
        }
        return new Trajectory(toTurnView(t), steps, observability.buildTraceUrl(t.getTraceparent()));
    }

    @Override
    public List<TurnSummary> turns(String threadId, int page, int size) {
        requireThreadVisible(threadId);
        int p = Math.max(1, page <= 0 ? 1 : page);
        int s = Math.min(100, Math.max(1, size <= 0 ? 20 : size));
        List<RuntimeTurnDO> rows = turns.listByThread(threadId, (p - 1) * s, s);
        List<TurnSummary> out = new ArrayList<>(rows.size());
        for (RuntimeTurnDO t : rows) {
            long cost = costs.turnUsage(t.getId()).stream()
                    .mapToLong(u -> u.costMicros() == null ? 0L : u.costMicros())
                    .sum();
            out.add(new TurnSummary(
                    t.getId(),
                    t.getSeq() == null ? 0 : t.getSeq(),
                    TurnStatus.valueOf(t.getStatus()),
                    t.getOrigin(),
                    t.getStepCount() == null ? 0 : t.getStepCount(),
                    t.getPromptTokens(),
                    t.getCompletionTokens(),
                    cost,
                    toInstant(t.getStartedAt()),
                    toInstant(t.getEndedAt()),
                    t.getTaint() == null || t.getTaint().isBlank() ? "CLEAN" : t.getTaint()));
        }
        return out;
    }

    /** EVO-01：会话数据标签只读（DAT-02 / V164），轨迹导出的标签限制依据。 */
    @Override
    public java.util.Optional<String> dataLabel(String threadId) {
        RuntimeThreadDO s = threads.find(threadId);
        return s == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(s.getDataLabel());
    }

    private void requireThreadVisible(String threadId) {
        RuntimeThreadDO s = threads.find(threadId);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "thread 不存在");
        }
        Principal p = principals.current();
        if (p != null && p.userId() != null && s.getOwnerId() != null
                && !s.getOwnerId().equals(p.userId())
                && !"0".equals(p.userId())
                && !p.hasPermission(Permissions.WORKSPACE_MANAGE)
                && !p.hasPermission(Permissions.ALL)
                && !p.hasPermission(Permissions.TENANT_ADMIN)) {
            throw new BizException(ErrorCode.NOT_FOUND, "thread 不存在");
        }
    }

    private void guardOrchRead(String origin) {
        if (origin == null || !"orch".equalsIgnoreCase(origin)) {
            return;
        }
        Principal p = principals.current();
        if (p == null || (!p.hasPermission(Permissions.ORCH_READ)
                && !p.hasPermission(Permissions.ALL)
                && !p.hasPermission(Permissions.TENANT_ADMIN))) {
            throw new BizException(ErrorCode.FORBIDDEN, "origin=orch 需要 orch:read");
        }
    }

    private RuntimeApi.TurnView toTurnView(RuntimeTurnDO t) {
        return new RuntimeApi.TurnView(
                t.getId(), t.getThreadId(), t.getSeq() == null ? 0 : t.getSeq(),
                TurnStatus.valueOf(t.getStatus()), t.getOrigin(), t.getOriginRef(),
                t.getStepCount() == null ? 0 : t.getStepCount(),
                t.getErrorCode(), t.getErrorMessage(),
                t.getPromptTokens(), t.getCompletionTokens(),
                toInstant(t.getCreateAt()), toInstant(t.getStartedAt()), toInstant(t.getEndedAt()));
    }

    /** 轨迹正文查看门控：{@code redacted} 为真时仅回结构占位，不回正文。 */
    private RuntimeApi.ItemView toItemView(RuntimeItemDO item, boolean redacted) {
        Map<String, Object> content;
        if (redacted) {
            content = new LinkedHashMap<>();
            content.put("redacted", true);
        } else {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = mapper.readValue(
                        item.getContentJson() == null ? "{}" : item.getContentJson(), Map.class);
                content = new LinkedHashMap<>(c);
            } catch (Exception e) {
                content = new LinkedHashMap<>();
            }
        }
        if (Boolean.TRUE.equals(item.getDiscarded())) {
            content.put("discarded", true);
        }
        return new RuntimeApi.ItemView(
                item.getId(), item.getThreadId(), item.getTurnId(),
                item.getSeq() == null ? 0L : item.getSeq(),
                item.getStep() == null ? 0 : item.getStep(),
                ItemType.fromWire(item.getType()),
                item.getRole(), content,
                item.getCreateAt() == null ? Instant.EPOCH
                        : item.getCreateAt().atZone(ZoneOffset.UTC).toInstant());
    }

    /**
     * 调用者的正文可见上限（对齐 {@code TimelineController}）：平台级管理员或持
     * {@code trajectory:read:content} → {@code SECRET}，否则 {@code INTERNAL}。
     */
    private static DataLabel clearance(Principal p) {
        if (p == null) {
            return DataLabel.INTERNAL;
        }
        boolean platform = p.hasPermission(Permissions.TENANT_ADMIN)
                || p.hasPermission(Permissions.ALL)
                || p.roles().contains(SystemRoles.SYSTEM_ADMIN);
        return platform || p.hasPermission(Permissions.TRAJECTORY_READ_CONTENT)
                ? DataLabel.SECRET : DataLabel.INTERNAL;
    }

    private static DataLabel parseLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DataLabel l : DataLabel.values()) {
            if (l.name().equalsIgnoreCase(raw.trim())) {
                return l;
            }
        }
        return null;
    }

    /** 实际读到受门控正文（{@code INTERNAL} 以上条目）时写一条分类 {@code DATA} 的审计。 */
    private void auditContentView(RuntimeTurnDO turn, int itemCount) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Principal p = principals.current();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("threadId", turn.getThreadId());
        details.put("turnId", turn.getId());
        details.put("itemCount", itemCount);
        details.put("permission", Permissions.TRAJECTORY_READ_CONTENT);
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action(ACTION_READ_CONTENT)
                .resourceType("turn")
                .resourceId(turn.getId())
                .outcome(AuditOutcome.SUCCESS)
                .turnId(turn.getId())
                .service(observability.getServiceName())
                .details(details);
        if (p != null) {
            b.workspaceId(p.workspaceId())
                    .actorType(p.type().name().toLowerCase(Locale.ROOT))
                    .actorId(p.userId());
        }
        AuditEvent event = b.build();
        try {
            api.record(event);
        } catch (RuntimeException e) {
            AuditDegradedSink sink = degradedSink.getIfAvailable();
            if (sink != null) {
                sink.append(event);
            } else {
                log.error("轨迹正文查看审计写入失败且无降级 sink：{}", e.toString());
            }
        }
    }

    private static CostQueryApi.UsageEntry mergeUsage(CostQueryApi.UsageEntry a, CostQueryApi.UsageEntry b) {
        boolean failover = (a.status() != null && a.status().toUpperCase().contains("FAILOVER"))
                || (b.status() != null && b.status().toUpperCase().contains("FAILOVER"));
        String status = failover ? "FAILOVER" : (b.status() != null ? b.status() : a.status());
        return new CostQueryApi.UsageEntry(
                b.turnId() != null ? b.turnId() : a.turnId(),
                b.step() != null ? b.step() : a.step(),
                b.modelRef() != null ? b.modelRef() : a.modelRef(),
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                (a.costMicros() == null ? 0L : a.costMicros()) + (b.costMicros() == null ? 0L : b.costMicros()),
                Math.max(a.latencyMs() == null ? 0 : a.latencyMs(), b.latencyMs() == null ? 0 : b.latencyMs()),
                status);
    }

    private static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}

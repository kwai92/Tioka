package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.child.SubAgentReaper;
import com.tioka.runtime.kernel.domain.AgentLoop;
import com.tioka.runtime.kernel.domain.CancelledSignal;
import com.tioka.runtime.kernel.domain.LeaseLost;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.domain.LoopResult;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.ThreadLock;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import com.tioka.runtime.api.event.TurnCompletedEvent;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import com.tioka.system.api.spi.QuotaGuard;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TurnRunner {
    private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

    private final TurnRepository turns;
    private final RuntimeThreadRepository threads;
    private final ThreadLock threadLock;
    private final AgentLoop agentLoop;
    private final PrincipalHolder principals;
    private final PrincipalLoaderApi principalLoader;
    private final ObjectMapper mapper;
    private final KernelProperties props;
    private final ScheduledExecutorService renewScheduler;
    private final SignalBus signalBus;
    private final LoopPorts.SignalPort signalPort;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectProvider<RuntimeTestHooks> testHooks;
    private final TurnDispatchQueue dispatchQueue;
    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;
    private final QuotaGuard quota;
    private final ApplicationEventPublisher appEvents;
    private final ObjectProvider<SubAgentReaper> reaper;

    public TurnRunner(TurnRepository turns,
                      RuntimeThreadRepository threads,
                      ThreadLock threadLock,
                      AgentLoop agentLoop,
                      PrincipalHolder principals,
                      PrincipalLoaderApi principalLoader,
                      ObjectMapper mapper,
                      KernelProperties props,
                      ScheduledExecutorService turnLeaseRenewScheduler,
                      SignalBus signalBus,
                      LoopPorts.SignalPort signalPort,
                      EventStream streams,
                      RuntimeEventFactory events,
                      ObjectProvider<RuntimeTestHooks> testHooks,
                      TurnDispatchQueue dispatchQueue,
                      ObjectProvider<AgentSnapshotApi> agentSnapshots,
                      QuotaGuard quota,
                      ApplicationEventPublisher appEvents,
                      ObjectProvider<SubAgentReaper> reaper) {
        this.turns = turns;
        this.threads = threads;
        this.threadLock = threadLock;
        this.agentLoop = agentLoop;
        this.principals = principals;
        this.principalLoader = principalLoader;
        this.mapper = mapper;
        this.props = props;
        this.renewScheduler = turnLeaseRenewScheduler;
        this.signalBus = signalBus;
        this.signalPort = signalPort;
        this.streams = streams;
        this.events = events;
        this.testHooks = testHooks;
        this.dispatchQueue = dispatchQueue;
        this.agentSnapshots = agentSnapshots;
        this.quota = quota;
        this.appEvents = appEvents;
        this.reaper = reaper;
    }

    public void run(String turnId) {
        RuntimeTurnDO peek = turnId == null ? null : turns.findById(turnId).orElse(null);
        SpanBuilder builder = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("turn.run")
                .setAttribute("tioka.turn_id", turnId == null ? "" : turnId);
        // OBS-03 / ADR-073：审批挂起后续跑新开 trace，用 span link 指回原 turn.run
        SpanContext prior = peek == null ? SpanContext.getInvalid() : parseTraceparent(peek.getTraceparent());
        if (prior.isValid()) {
            builder.addLink(prior);
        }
        if (peek != null && peek.getRequestId() != null && !peek.getRequestId().isBlank()) {
            builder.setAttribute("tioka.request_id", peek.getRequestId());
        }
        if (peek != null && peek.getSubject() != null && !peek.getSubject().isBlank()) {
            builder.setAttribute("tioka.subject", peek.getSubject());
        }
        Span span = builder.startSpan();
        RequestContext previous = RequestContextHolder.getCorrelation();
        boolean restored = false;
        try {
            if (peek != null && peek.getRequestId() != null && !peek.getRequestId().isBlank()) {
                RequestContextHolder.setCorrelation(
                        new RequestContext(peek.getRequestId(), peek.getSubject(), null));
                restored = true;
            }
            try (Scope ignored = span.makeCurrent()) {
                runTracked(turnId, span, peek);
            }
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            if (restored) {
                RequestContextHolder.setCorrelation(previous);
            }
            span.end();
        }
    }

    private void runTracked(String turnId, Span span, RuntimeTurnDO preloaded) {
        RuntimeTurnDO turn = preloaded != null ? preloaded : turns.findById(turnId).orElse(null);
        if (turn == null) {
            return;
        }
        String threadId = turn.getThreadId();
        span.setAttribute("tioka.thread_id", threadId == null ? "" : threadId);
        if (turn.getOrigin() != null) {
            span.setAttribute("tioka.origin", turn.getOrigin());
        }
        Optional<ThreadLock.Handle> lockOpt = threadLock.tryAcquire(threadId, turnId);
        if (lockOpt.isEmpty()) {
            return;
        }
        ThreadLock.Handle lock = lockOpt.get();
        AtomicBoolean lost = new AtomicBoolean(false);
        ScheduledFuture<?> renewFuture = null;
        long leaseVersion = -1;
        boolean finished = false;
        boolean holdConcurrent = false;
        String quotaWs = null;
        String quotaUser = null;
        try {
            // 派发线程尚无 Principal：不可查 workspace 作用域表，从 run_as 解析配额 scope
            quotaWs = peekRunAsWorkspaceId(turn.getRunAsJson());
            quotaUser = peekRunAsUserId(turn.getRunAsJson());
            Instant leaseUntil = Instant.now().plus(props.getLeaseTtl());
            // GOV-03：并发占位成功才 acquire；失败保持 QUEUED，5 秒后重投
            if (!quota.tryAcquireConcurrent(quotaWs, quotaUser, turnId, leaseUntil)) {
                log.debug("concurrent quota full, requeue in 5s turnId={}", turnId);
                renewScheduler.schedule(() -> dispatchQueue.offer(turnId, threadId), 5, TimeUnit.SECONDS);
                return;
            }
            holdConcurrent = true;

            var acquired = turns.acquire(turnId, props.getInstanceId(), leaseUntil);
            if (acquired.isEmpty()) {
                return;
            }
            leaseVersion = acquired.getAsLong();
            turns.saveTraceparent(turnId, formatTraceparent(span.getSpanContext()));
            long lv = leaseVersion;
            String ws = quotaWs;
            String user = quotaUser;
            renewFuture = renewScheduler.scheduleAtFixedRate(() -> {
                RuntimeTestHooks hooks = testHooks.getIfAvailable();
                if (hooks != null && hooks.isFrozen()) {
                    return;
                }
                Instant next = Instant.now().plus(props.getLeaseTtl());
                boolean ok = lock.renew()
                        && turns.renew(turnId, lv, next) > 0;
                if (ok) {
                    quota.renewConcurrent(ws, user, turnId, next);
                } else {
                    lost.set(true);
                }
            }, props.getLeaseRenewInterval().toMillis(), props.getLeaseRenewInterval().toMillis(), TimeUnit.MILLISECONDS);

            signalBus.register(turnId);
            try {
                // 必须先 resolveRunAs：threads.find 走行级过滤，无 Principal 会炸
                // R-1：runAs 无效时立刻 FAILED(RUN_AS_INVALID)，释放配额，不续跑
                Principal runAs;
                try {
                    runAs = resolveRunAs(turn);
                } catch (RuntimeException e) {
                    log.warn("turn {} runAs invalid: {}", turnId, e.toString());
                    failTurn(turnId, lv, threadId, "RUN_AS_INVALID", e.getMessage());
                    finished = true;
                    return;
                }
                long finalLv = lv;
                LoopResult result = principals.runAs(runAs, () -> {
                    RuntimeThreadDO thread = threads.find(threadId);
                    if (thread != null && thread.getAgentVersionId() != null) {
                        span.setAttribute("tioka.agent_version_id", thread.getAgentVersionId());
                    }
                    int maxSteps = parseMaxSteps(turn.getOptionsJson(), props.getLoop().getMaxSteps());
                    // RT-06：崩溃续跑从已累计的 step_count 继续（中断前 model 步已 increment）
                    int startStep = turn.getStepCount() == null ? 0 : turn.getStepCount();
                    String approvalPolicy = parseApprovalPolicy(turn.getOptionsJson());
                    List<String> toolAllowList = parseToolAllowList(turn.getOptionsJson());
                    Map<String, Object> agentToolConfig = agentToolConfigOf(
                            thread == null ? null : thread.getAgentVersionId());
                    String origin = thread == null ? null : thread.getOrigin();
                    Instant deadline = resolveDeadline(turn, origin);
                    LoopContext ctx = new LoopContext(threadId, turnId, finalLv,
                            maxSteps, props.getLoop().getRepeatCallLimit(), startStep,
                            c -> {
                                if (lost.get()) {
                                    throw new LeaseLost();
                                }
                                signalPort.checkpoint(c);
                            },
                            thread == null ? null : thread.getAgentVersionId(),
                            thread == null ? null : thread.getRoutingPlanId(),
                            thread == null ? null : thread.getWorkspaceId(),
                            runAs.userId(),
                            approvalPolicy,
                            origin,
                            toolAllowList,
                            agentToolConfig,
                            deadline,
                            thread == null ? null : thread.getGrantId());
                    return agentLoop.run(ctx);
                });
                if (result.status() == TurnStatus.WAITING_APPROVAL
                        || result.status() == TurnStatus.WAITING_CHILD) {
                    // 挂起已在 suspend 服务内完成；释放锁与并发槽，不 enqueueNext
                    quota.releaseConcurrent(quotaWs, quotaUser, turnId);
                    holdConcurrent = false;
                } else {
                    int n = turns.finish(turnId, finalLv, result.status(),
                            result.errorCode(), truncate(result.errorMessage()),
                            result.promptTokens(), result.completionTokens());
                    turns.requireLeaseWrite(n);
                    publishTerminal(threadId, turnId, result.status());
                    reapChild(threadId, turnId, result.status());
                    finished = true;
                }
            } catch (LeaseLost e) {
                log.warn("turn {} lease lost; leave terminal to reaper", turnId);
                // 槽位随 ZSet score 过期清理；不在此释放，避免假死复活双占
                holdConcurrent = false;
            } catch (CancelledSignal e) {
                turns.finish(turnId, lv, TurnStatus.CANCELLED, e.reason(), null, null, null);
                publishTerminal(threadId, turnId, TurnStatus.CANCELLED);
                reapChild(threadId, turnId, TurnStatus.CANCELLED);
                finished = true;
            } catch (RuntimeException e) {
                Throwable root = unwrap(e);
                RuntimeTestHooks hooks = testHooks.getIfAvailable();
                if (root instanceof RuntimeTestHooks.CrashFrozenAbort
                        || (hooks != null && hooks.isFrozen())
                        || !props.isReleaseOnShutdown()) {
                    log.warn("turn {} left for reaper after crash abort: {}", turnId, root.toString());
                    holdConcurrent = false;
                } else {
                    log.error("turn {} failed", turnId, e);
                    try {
                        turns.finish(turnId, lv, TurnStatus.FAILED, "INTERNAL",
                                truncate(e.getMessage()), null, null);
                        publishTerminal(threadId, turnId, TurnStatus.FAILED);
                        reapChild(threadId, turnId, TurnStatus.FAILED);
                        finished = true;
                    } catch (RuntimeException ignored) {
                        // ignore
                    }
                }
            } finally {
                signalBus.unregister(turnId);
            }
        } finally {
            if (holdConcurrent) {
                quota.releaseConcurrent(quotaWs, quotaUser, turnId);
            }
            if (renewFuture != null) {
                renewFuture.cancel(false);
            }
            if (props.isReleaseOnShutdown()) {
                lock.close();
            }
            // M1-02：Turn 结束后查 nextQueued，有则重新 XADD
            if (finished) {
                enqueueNext(threadId);
            }
        }
    }

    private static String formatTraceparent(SpanContext ctx) {
        if (ctx == null || !ctx.isValid()) {
            return null;
        }
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-"
                + (ctx.getTraceFlags().isSampled() ? "01" : "00");
    }

    /** 解析 runtime_turn.traceparent；非法则返回 invalid。 */
    private static SpanContext parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return SpanContext.getInvalid();
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4) {
            return SpanContext.getInvalid();
        }
        try {
            TraceFlags flags = "01".equals(parts[3]) ? TraceFlags.getSampled() : TraceFlags.getDefault();
            return SpanContext.create(parts[1], parts[2], flags, TraceState.getDefault());
        } catch (RuntimeException e) {
            return SpanContext.getInvalid();
        }
    }

    /** LeaseReaper / 外部在终态后触发同 thread 下一条。 */
    public void enqueueNext(String threadId) {
        turns.nextQueued(threadId).ifPresent(t -> dispatchQueue.offer(t.getId(), threadId));
    }

    /** MAS-03：子 Turn 终态回收（非子 thread 空操作；失败仅告警不影响 Turn 终态）。 */
    private void reapChild(String threadId, String turnId, TurnStatus status) {
        SubAgentReaper r = reaper.getIfAvailable();
        if (r == null) {
            return;
        }
        try {
            // C-17 修复：reap 由 Turn 终态后台路径触发，此处已退出 runAs(runAsJson) 块，
            // 线程无 Principal —— SubAgentReaper 访问 runtime_approval（带 workspace_id，
            // 非 scope-exempt）会被租户行拦截器以「无 Principal 的数据访问」拒绝，导致
            // 授予永不 REAPED、父 Turn 永久 WAITING_CHILD。按框架「无 workspace 的 SYSTEM
            // Principal 整表旁路（后台扫表）」口径以 SYSTEM 身份执行回收。
            principals.runAs(SystemPrincipals.system(), () -> r.onChildTerminal(threadId, turnId, status));
        } catch (RuntimeException e) {
            log.warn("subagent reap failed thread={} turn={}: {}", threadId, turnId, e.toString());
        }
    }

    private void failTurn(String turnId, long leaseVersion, String threadId,
                          String errorCode, String errorMessage) {
        try {
            turns.finish(turnId, leaseVersion, TurnStatus.FAILED, errorCode,
                    truncate(errorMessage), null, null);
            publishTerminal(threadId, turnId, TurnStatus.FAILED);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void publishTerminal(String threadId, String turnId, TurnStatus status) {
        RuntimeEventType type = switch (status) {
            case COMPLETED -> RuntimeEventType.TURN_COMPLETED;
            case FAILED -> RuntimeEventType.TURN_FAILED;
            case CANCELLED -> RuntimeEventType.TURN_CANCELLED;
            default -> RuntimeEventType.TURN_COMPLETED;
        };
        streams.publish(threadId, null, type.wire(),
                events.turnStatus(type, threadId, turnId, status.name()));
        if (status == TurnStatus.COMPLETED) {
            RuntimeTurnDO done = turns.findById(turnId).orElse(null);
            appEvents.publishEvent(new TurnCompletedEvent(
                    turnId,
                    threadId,
                    done == null ? null : done.getOrigin(),
                    done == null ? null : done.getOriginRef()));
        }
    }

    /** 仅解析字段供并发配额占位；完整 Principal 仍走 {@link #resolveRunAs}。 */
    private String peekRunAsUserId(String runAsJson) {
        return peekRunAsField(runAsJson, "userId");
    }

    private String peekRunAsWorkspaceId(String runAsJson) {
        return peekRunAsField(runAsJson, "workspaceId");
    }

    private String peekRunAsField(String runAsJson, String field) {
        if (runAsJson == null || runAsJson.isBlank()) {
            return null;
        }
        try {
            String v = mapper.readTree(runAsJson).path(field).asText(null);
            return v == null || v.isBlank() || "0".equals(v) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** ADR-029 §6：按 turn.runAsJson 重载发起人权限，禁止一律 SYSTEM。 */
    public Principal resolveRunAs(RuntimeTurnDO turn) {
        String json = turn.getRunAsJson();
        if (json == null || json.isBlank()) {
            return SystemPrincipals.system();
        }
        try {
            JsonNode n = mapper.readTree(json);
            String type = n.path("type").asText(PrincipalType.USER.name());
            if (PrincipalType.SYSTEM.name().equals(type)) {
                return SystemPrincipals.system();
            }
            String userId = n.path("userId").asText(null);
            String workspaceId = n.path("workspaceId").asText(null);
            if (userId == null || userId.isBlank() || "0".equals(userId)) {
                return SystemPrincipals.system();
            }
            if (workspaceId == null || workspaceId.isBlank()) {
                throw new BizException(ErrorCode.INTERNAL, "turn.runAs 缺少 workspaceId");
            }
            // MAS-01（ADR-039）：SERVICE 模式带经办人 → 服务账号 ∩ 经办人 权限交集
            String onBehalfOf = n.path("onBehalfOf").asText(null);
            if (onBehalfOf != null && !onBehalfOf.isBlank()) {
                return principalLoader.loadEffective(Long.parseLong(userId), workspaceId, onBehalfOf);
            }
            return principalLoader.load(Long.parseLong(userId), workspaceId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "无法解析 turn.runAs: " + e.getMessage());
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null
                && (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)) {
            cur = cur.getCause();
        }
        return cur;
    }

    private int parseMaxSteps(String optionsJson, int defaultMax) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return defaultMax;
        }
        try {
            JsonNode n = mapper.readTree(optionsJson).path("maxSteps");
            if (n.isNumber()) {
                return Math.min(50, Math.max(1, n.asInt(defaultMax)));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return defaultMax;
    }

    /**
     * RT-09：默认 30 分钟；origin=orch|job 上限 4 小时，其他 1 小时。
     */
    private Instant resolveDeadline(RuntimeTurnDO turn, String origin) {
        Duration requested = parseMaxDuration(turn.getOptionsJson());
        if (requested == null) {
            requested = Duration.ofMinutes(30);
        }
        Duration cap = ("orch".equalsIgnoreCase(origin) || "job".equalsIgnoreCase(origin))
                ? Duration.ofHours(4) : Duration.ofHours(1);
        if (requested.compareTo(cap) > 0) {
            requested = cap;
        }
        Instant start = turn.getStartedAt() == null
                ? Instant.now()
                : turn.getStartedAt().toInstant(java.time.ZoneOffset.UTC);
        return start.plus(requested);
    }

    private Duration parseMaxDuration(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(optionsJson).path("maxDuration");
            if (n.isMissingNode() || n.isNull() || n.asText("").isBlank()) {
                return null;
            }
            return Duration.parse(n.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private String parseApprovalPolicy(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(optionsJson);
            String p = n.path("approvalPolicy").asText(null);
            return p == null || p.isBlank() ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseToolAllowList(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode n = mapper.readTree(optionsJson).path("toolAllowList");
            if (!n.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode item : n) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    out.add(item.asText());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** CON-01：从 Agent 快照抽出 McpBinding 列表，写入 agentToolConfig.mcp（47-GL）。 */
    private Map<String, Object> agentToolConfigOf(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            return Map.of();
        }
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (snapshots == null) {
            return Map.of();
        }
        try {
            AgentSpec.ToolSet tools = snapshots.get(agentVersionId).spec().tools();
            if (tools == null || tools.mcp() == null || tools.mcp().isEmpty()) {
                return Map.of("mcp", List.of());
            }
            List<Map<String, Object>> bindings = new ArrayList<>();
            for (AgentSpec.ToolSet.McpBinding b : tools.mcp()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("serverId", b.serverId());
                g.put("allowlist", b.allowlist() == null ? List.of() : b.allowlist());
                bindings.add(g);
            }
            return Map.of("mcp", bindings);
        } catch (RuntimeException e) {
            log.warn("load agentToolConfig failed version={}: {}", agentVersionId, e.toString());
            return Map.of();
        }
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= 500 ? msg : msg.substring(0, 500);
    }
}

package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.domain.AgentLoop;
import com.tioka.runtime.kernel.domain.CancelledSignal;
import com.tioka.runtime.kernel.domain.LeaseLost;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.domain.LoopResult;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.SessionLock;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
    private final RuntimeSessionRepository sessions;
    private final SessionLock sessionLock;
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

    public TurnRunner(TurnRepository turns,
                      RuntimeSessionRepository sessions,
                      SessionLock sessionLock,
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
                      ObjectProvider<AgentSnapshotApi> agentSnapshots) {
        this.turns = turns;
        this.sessions = sessions;
        this.sessionLock = sessionLock;
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
    }

    public void run(String turnId) {
        RuntimeTurnDO turn = turns.findById(turnId).orElse(null);
        if (turn == null) {
            return;
        }
        String threadId = turn.getSessionId();
        Optional<SessionLock.Handle> lockOpt = sessionLock.tryAcquire(threadId, turnId);
        if (lockOpt.isEmpty()) {
            return;
        }
        SessionLock.Handle lock = lockOpt.get();
        AtomicBoolean lost = new AtomicBoolean(false);
        ScheduledFuture<?> renewFuture = null;
        long leaseVersion = -1;
        boolean finished = false;
        try {
            var acquired = turns.acquire(turnId, props.getInstanceId(), Instant.now().plus(props.getLeaseTtl()));
            if (acquired.isEmpty()) {
                return;
            }
            leaseVersion = acquired.getAsLong();
            long lv = leaseVersion;
            renewFuture = renewScheduler.scheduleAtFixedRate(() -> {
                RuntimeTestHooks hooks = testHooks.getIfAvailable();
                if (hooks != null && hooks.isFrozen()) {
                    return;
                }
                boolean ok = lock.renew()
                        && turns.renew(turnId, lv, Instant.now().plus(props.getLeaseTtl())) > 0;
                if (!ok) {
                    lost.set(true);
                }
            }, props.getLeaseRenewInterval().toMillis(), props.getLeaseRenewInterval().toMillis(), TimeUnit.MILLISECONDS);

            signalBus.register(turnId);
            // 派发线程无请求 SecurityContext：必须先按 run_as 恢复 Principal，再读 workspace 作用域表
            Principal runAs = resolveRunAs(turn);
            try {
                long finalLv = lv;
                LoopResult result = principals.runAs(runAs, () -> {
                    RuntimeSessionDO session = sessions.find(threadId);
                    int maxSteps = props.getLoop().getMaxSteps();
                    // RT-06：崩溃续跑从已累计的 step_count 继续（中断前 model 步已 increment）
                    int startStep = turn.getStepCount() == null ? 0 : turn.getStepCount();
                    String approvalPolicy = parseApprovalPolicy(turn.getOptionsJson());
                    List<String> toolAllowList = parseToolAllowList(turn.getOptionsJson());
                    Map<String, Object> agentToolConfig = agentToolConfigOf(
                            session == null ? null : session.getAgentVersionId());
                    LoopContext ctx = new LoopContext(threadId, turnId, finalLv,
                            maxSteps, props.getLoop().getRepeatCallLimit(), startStep,
                            c -> {
                                if (lost.get()) {
                                    throw new LeaseLost();
                                }
                                signalPort.checkpoint(c);
                            },
                            session == null ? null : session.getAgentVersionId(),
                            session == null ? null : session.getRoutingPlanId(),
                            session == null ? null : session.getWorkspaceId(),
                            runAs.userId(),
                            approvalPolicy,
                            session == null ? null : session.getOrigin(),
                            toolAllowList,
                            agentToolConfig);
                    return agentLoop.run(ctx);
                });
                if (result.status() == TurnStatus.WAITING_APPROVAL) {
                    // 挂起已在 ApprovalSuspendService 内完成；释放锁与并发槽，不 enqueueNext
                } else {
                    int n = turns.finish(turnId, finalLv, result.status(),
                            result.errorCode(), truncate(result.errorMessage()),
                            result.promptTokens(), result.completionTokens());
                    turns.requireLeaseWrite(n);
                    publishTerminal(threadId, turnId, result.status());
                    finished = true;
                }
            } catch (LeaseLost e) {
                log.warn("turn {} lease lost; leave terminal to reaper", turnId);
            } catch (CancelledSignal e) {
                turns.finish(turnId, lv, TurnStatus.CANCELLED, e.reason(), null, null, null);
                publishTerminal(threadId, turnId, TurnStatus.CANCELLED);
                finished = true;
            } catch (RuntimeException e) {
                Throwable root = unwrap(e);
                RuntimeTestHooks hooks = testHooks.getIfAvailable();
                if (root instanceof RuntimeTestHooks.CrashFrozenAbort
                        || (hooks != null && hooks.isFrozen())
                        || !props.isReleaseOnShutdown()) {
                    log.warn("turn {} left for reaper after crash abort: {}", turnId, root.toString());
                } else {
                    log.error("turn {} failed", turnId, e);
                    try {
                        turns.finish(turnId, lv, TurnStatus.FAILED, "INTERNAL",
                                truncate(e.getMessage()), null, null);
                        publishTerminal(threadId, turnId, TurnStatus.FAILED);
                        finished = true;
                    } catch (RuntimeException ignored) {
                        // ignore
                    }
                }
            } finally {
                signalBus.unregister(turnId);
            }
        } finally {
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

    /** LeaseReaper / 外部在终态后触发同 thread 下一条。 */
    public void enqueueNext(String threadId) {
        turns.nextQueued(threadId).ifPresent(t -> dispatchQueue.offer(t.getId(), threadId));
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

    /** CON-01：从 Agent 快照抽出 mcpGrants 放入 ToolCallContext.agentToolConfig。 */
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
                return Map.of("mcpGrants", List.of());
            }
            List<Map<String, Object>> grants = new ArrayList<>();
            for (AgentSpec.ToolSet.McpBinding b : tools.mcp()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("serverId", b.serverId());
                g.put("allowlist", b.allowlist() == null ? List.of() : b.allowlist());
                grants.add(g);
            }
            return Map.of("mcpGrants", grants);
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

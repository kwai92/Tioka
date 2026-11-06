package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                      TurnDispatchQueue dispatchQueue) {
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
                principals.runAs(runAs, () -> {
                    RuntimeSessionDO session = sessions.find(threadId);
                    int maxSteps = props.getLoop().getMaxSteps();
                    LoopContext ctx = new LoopContext(threadId, turnId, finalLv,
                            maxSteps, props.getLoop().getRepeatCallLimit(), 0,
                            c -> {
                                if (lost.get()) {
                                    throw new LeaseLost();
                                }
                                signalPort.checkpoint(c);
                            },
                            session == null ? null : session.getAgentVersionId(),
                            session == null ? null : session.getRoutingPlanId(),
                            session == null ? null : session.getWorkspaceId(),
                            runAs.userId());
                    LoopResult result = agentLoop.run(ctx);
                    int n = turns.finish(turnId, finalLv, result.status(),
                            result.errorCode(), truncate(result.errorMessage()),
                            result.promptTokens(), result.completionTokens());
                    turns.requireLeaseWrite(n);
                    publishTerminal(threadId, turnId, result.status());
                    return null;
                });
                finished = true;
            } catch (LeaseLost e) {
                log.warn("turn {} lease lost; leave terminal to reaper", turnId);
            } catch (CancelledSignal e) {
                turns.finish(turnId, lv, TurnStatus.CANCELLED, null, null, null, null);
                publishTerminal(threadId, turnId, TurnStatus.CANCELLED);
                finished = true;
            } catch (RuntimeException e) {
                log.error("turn {} failed", turnId, e);
                try {
                    turns.finish(turnId, lv, TurnStatus.FAILED, "INTERNAL",
                            truncate(e.getMessage()), null, null);
                    publishTerminal(threadId, turnId, TurnStatus.FAILED);
                    finished = true;
                } catch (RuntimeException ignored) {
                    // ignore
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

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= 500 ? msg : msg.substring(0, 500);
    }
}

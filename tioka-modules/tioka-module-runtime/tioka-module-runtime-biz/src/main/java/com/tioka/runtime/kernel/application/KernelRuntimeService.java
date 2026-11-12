package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.framework.id.Ids;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.grant.MandateScope;
import com.tioka.runtime.api.kernel.ItemType;
import org.springframework.beans.factory.ObjectProvider;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.approval.ApprovalDecideService;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class KernelRuntimeService implements RuntimeApi {
    private static final Logger log = LoggerFactory.getLogger(KernelRuntimeService.class);

    private final RuntimeThreadRepository threads;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final TurnDispatchQueue dispatchQueue;
    private final TurnSignalService signals;
    private final PrincipalHolder principals;
    private final KernelProperties props;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectMapper mapper;
    private final AgentSnapshotApi agents;
    private final ApprovalDecideService approvalDecide;
    private final GrantService grants;
    private final ToolRegistry tools;
    private final ObjectProvider<com.tioka.governance.api.charter.AgentCharterPort> charterPort;

    public KernelRuntimeService(RuntimeThreadRepository threads,
                                TurnRepository turns,
                                ItemRepository items,
                                TurnDispatchQueue dispatchQueue,
                                TurnSignalService signals,
                                PrincipalHolder principals,
                                KernelProperties props,
                                EventStream streams,
                                RuntimeEventFactory events,
                                ObjectMapper mapper,
                                AgentSnapshotApi agents,
                                ApprovalDecideService approvalDecide,
                                GrantService grants,
                                ToolRegistry tools,
                                ObjectProvider<com.tioka.governance.api.charter.AgentCharterPort> charterPort) {
        this.threads = threads;
        this.turns = turns;
        this.items = items;
        this.dispatchQueue = dispatchQueue;
        this.signals = signals;
        this.principals = principals;
        this.props = props;
        this.streams = streams;
        this.events = events;
        this.mapper = mapper;
        this.agents = agents;
        this.approvalDecide = approvalDecide;
        this.grants = grants;
        this.tools = tools;
        this.charterPort = charterPort;
    }

    @Override
    @Transactional
    public ThreadView createThread(CreateThreadCmd cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CreateThreadCmd 必填");
        }
        requireText(cmd.agentVersionId(), "agentVersionId");
        Principal p = principals.current();
        AgentSnapshotApi.AgentSnapshot ver = agents.get(cmd.agentVersionId());
        // TRU-03（ADR-032）：SUSPENDED / HANDOVER 的新建 thread → AGENT_SUSPENDED(42301)
        requireCharterActive(ver);
        String id = Ids.newId("thr");
        RuntimeThreadDO row = new RuntimeThreadDO();
        row.setId(id);
        row.setStatus("OPEN");
        row.setAgentVersionId(ver.versionId());
        row.setRoutingPlanId(ver.spec().model().routingPlanId());
        row.setWorkspaceId(p.workspaceId());
        row.setOwnerId(p.userId());
        row.setLastItemSeq(0L);
        row.setOrigin(blankToNull(cmd.origin()) == null ? "chat" : cmd.origin());
        row.setOriginRef(cmd.originRef());
        row.setDepth(0);
        if (cmd.metadata() != null && !cmd.metadata().isEmpty()) {
            try {
                row.setMetadataJson(mapper.writeValueAsString(cmd.metadata()));
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "metadata 无法序列化");
            }
        }
        threads.upsert(row);
        return toThreadView(row);
    }

    @Override
    @Transactional
    public TurnHandle startTurn(String threadId, StartTurnCmd cmd) {
        requireText(threadId, "threadId");
        if (cmd == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "StartTurnCmd 必填");
        }
        requireText(cmd.clientMsgId(), "clientMsgId");
        RuntimeThreadDO thread = requireThread(threadId);
        // TRU-03（ADR-032）：SUSPENDED / HANDOVER 的新 Turn → AGENT_SUSPENDED(42301)
        requireCharterActive(agents.get(thread.getAgentVersionId()));
        var existing = turns.findByThreadAndClientMsgId(threadId, cmd.clientMsgId());
        if (existing.isPresent()) {
            RuntimeTurnDO t = existing.get();
            return new TurnHandle(t.getId(), TurnStatus.valueOf(t.getStatus()), eventsUrl(threadId));
        }
        int active = turns.countActive(threadId);
        if (active >= props.getMaxQueuedPerThread()) {
            throw new BizException(ErrorCode.RATE_LIMITED, "同 thread 排队超过上限");
        }
        String turnId = Ids.newId("turn");
        RequestContext correlation = ensureCorrelation();
        // MAS-01：新 thread 第一个 Turn 签发根授予；同 thread 后续 Turn 复用（过期重签）
        ensureRootGrant(threadId);
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(turnId);
        t.setThreadId(threadId);
        t.setSeq(turns.nextSeq(threadId));
        t.setStatus(TurnStatus.QUEUED.name());
        t.setOrigin(blankToNull(cmd.origin()) == null ? "chat" : cmd.origin());
        t.setOriginRef(cmd.originRef());
        t.setClientMsgId(cmd.clientMsgId());
        t.setRunAsJson(runAsJson(cmd));
        t.setOptionsJson(optionsJson(cmd.options()));
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        t.setRequestId(correlation.requestId());
        t.setSubject(correlation.subject());
        try {
            turns.insert(t);
        } catch (DuplicateKeyException e) {
            RuntimeTurnDO again = turns.findByThreadAndClientMsgId(threadId, cmd.clientMsgId()).orElseThrow();
            return new TurnHandle(again.getId(), TurnStatus.valueOf(again.getStatus()), eventsUrl(threadId));
        }
        if (cmd.input() != null) {
            for (InputItem in : cmd.input()) {
                if (in == null || in.text() == null) {
                    continue;
                }
                String type = in.type() == null ? ItemType.MESSAGE.wire() : in.type();
                items.appendWithoutLease(ItemWrite.withoutLease(threadId, turnId, 0, type, "user",
                        json(Map.of("text", in.text()))));
            }
        }
        streams.publish(threadId, null, RuntimeEventType.TURN_STARTED.wire(),
                events.turnStatus(RuntimeEventType.TURN_STARTED, threadId, turnId, TurnStatus.QUEUED.name()));
        // 必须在事务提交后再 XADD，否则 worker 可能读不到刚 insert 的 Turn（永久 QUEUED）
        offerAfterCommit(turnId, threadId);
        return new TurnHandle(turnId, TurnStatus.QUEUED, eventsUrl(threadId));
    }

    private void offerAfterCommit(String turnId, String threadId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchQueue.offer(turnId, threadId);
                }
            });
        } else {
            dispatchQueue.offer(turnId, threadId);
        }
    }

    @Override
    public void interrupt(String turnId) {
        requireText(turnId, "turnId");
        RuntimeTurnDO t = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        requireThread(t.getThreadId());
        signals.interrupt(turnId);
    }

    @Override
    public void steer(String turnId, String text) {
        requireText(turnId, "turnId");
        RuntimeTurnDO t = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        requireThread(t.getThreadId());
        signals.steer(turnId, text);
    }

    @Override
    public void decideApproval(String approvalId, ApprovalDecision decision) {
        requireText(approvalId, "approvalId");
        if (decision == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ApprovalDecision 必填");
        }
        approvalDecide.decide(approvalId, decision);
    }

    @Override
    public TurnView getTurn(String turnId) {
        requireText(turnId, "turnId");
        RuntimeTurnDO t = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        requireThread(t.getThreadId());
        return toTurnView(t);
    }

    @Override
    public Optional<TurnView> findActiveTurn(String threadId) {
        requireText(threadId, "threadId");
        requireThread(threadId);
        return turns.findActive(threadId).map(this::toTurnView);
    }

    @Override
    public List<ItemView> listItems(String threadId, long afterSeq, int limit) {
        requireText(threadId, "threadId");
        if (afterSeq < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "afterSeq 不能为负");
        }
        requireThread(threadId);
        int capped = Math.min(Math.max(limit, 1), 500);
        return items.listAfterSeq(threadId, afterSeq, capped).stream()
                .map(this::toItemView)
                .toList();
    }

    @Override
    public ThreadView fork(String threadId, long atSeq) {
        requireText(threadId, "threadId");
        if (atSeq < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "atSeq 不能为负");
        }
        throw NotImplemented.of("runtime-fork", "M3");
    }

    private RuntimeThreadDO requireThread(String threadId) {
        RuntimeThreadDO s = threads.find(threadId);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "thread 不存在");
        }
        Principal p = principals.current();
        if (p != null && p.userId() != null && s.getOwnerId() != null
                && !s.getOwnerId().equals(p.userId())
                && !"0".equals(p.userId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "thread 不存在");
        }
        return s;
    }

    /**
     * TRU-03（ADR-032「负责人与生命周期」）：授权书非 ACTIVE（SUSPENDED / HANDOVER）时
     * 拒绝新建 thread 与 Turn。状态读经 {@link AgentCharterPort}（直连 DB，无快照缓存延迟，
     * 跨实例即时一致）；端口缺失（离线 mock 场景）回落快照内 {@code charterStatus}。
     */
    private void requireCharterActive(AgentSnapshotApi.AgentSnapshot ver) {
        if (ver == null) {
            return;
        }
        String status = null;
        var port = charterPort.getIfAvailable();
        if (port != null) {
            status = port.charterStatus(ver.agentId());
        }
        if (status == null) {
            status = ver.charterStatus();
        }
        if (!"ACTIVE".equals(status)) {
            throw new BizException(ErrorCode.AGENT_SUSPENDED,
                    "Agent 授权书状态为 " + status + "，暂停服务（TRU-03）");
        }
    }

    private String runAsJson(RuntimeApi.StartTurnCmd cmd) {
        Principal p = principals.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", p == null ? "0" : p.userId());
        m.put("workspaceId", p == null ? null : p.workspaceId());
        m.put("type", p == null ? "SYSTEM" : p.type().name());
        // MAS-01 / ADR-039：SERVICE 模式代表经办人，只存身份，工具调用前按定案 6 重载权限
        if (cmd != null && cmd.onBehalfOf() != null && !cmd.onBehalfOf().isBlank()) {
            m.put("onBehalfOf", cmd.onBehalfOf());
        }
        return json(m);
    }

    /**
     * MAS-01：thread 无授予（或已失效 / 过期）时签发根授予并写 runtime_thread.grant_id；
     * 工具、Skill、资源取自 Agent 版本快照（M3-01 L35-47）。
     */
    private void ensureRootGrant(String threadId) {
        RuntimeThreadDO thread = threads.find(threadId);
        if (thread == null) {
            return;
        }
        if (thread.getGrantId() != null && !thread.getGrantId().isBlank()) {
            DelegationGrant existing = grants.get(thread.getGrantId());
            if (existing != null && existing.status() == GrantStatus.ACTIVE
                    && (existing.expiresAt() == null || Instant.now().isBefore(existing.expiresAt()))) {
                return;
            }
        }
        if (thread.getAgentVersionId() == null || thread.getAgentVersionId().isBlank()) {
            return;
        }
        try {
            AgentSnapshotApi.AgentSnapshot snap = agents.get(thread.getAgentVersionId());
            GrantAgentView view = com.tioka.runtime.application.grant.GrantAgentViews.fromSnapshot(snap, tools);
            Principal runAs = principals.current();
            DelegationGrant grant = grants.root(runAs, null, view, MandateScope.empty());
            RuntimeThreadDO patch = new RuntimeThreadDO();
            patch.setId(threadId);
            patch.setGrantId(grant.grantId());
            threads.upsert(patch);
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                // 根授予签发失败不阻断 Turn（design-notes/M3-C2a-delegation.md §二「startTurn 根授予」：
                // grant_id 保持空，2.5 步 DelegationGrantCheck 对 null 放行），登记告警
                log.warn("root grant issuance failed for thread {}, grant_id stays empty: {}",
                        threadId, e.getMessage());
                return;
            }
            throw e;
        } catch (RuntimeException e) {
            // 根授予签发失败不阻断 Turn（thread.grant_id 保持空，2.5 步放行），登记告警
        }
    }

    private String optionsJson(TurnOptions options) {
        if (options == null) {
            return null;
        }
        int max = options.maxSteps() == null ? props.getLoop().getMaxSteps() : Math.min(50, options.maxSteps());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxSteps", max);
        m.put("approvalPolicy", options.approvalPolicy() == null ? "" : options.approvalPolicy());
        m.put("toolAllowList", options.toolAllowList() == null ? List.of() : options.toolAllowList());
        if (options.maxDuration() != null) {
            m.put("maxDuration", options.maxDuration().toString());
        }
        return json(m);
    }

    private String eventsUrl(String threadId) {
        return "/api/runtime/threads/" + threadId + "/events";
    }

    private ThreadView toThreadView(RuntimeThreadDO s) {
        Instant created = s.getCreateAt() == null ? Instant.EPOCH
                : s.getCreateAt().atZone(ZoneOffset.UTC).toInstant();
        return new ThreadView(s.getId(), s.getAgentVersionId(), s.getOrigin(), s.getOriginRef(),
                s.getParentTurnId(), s.getDepth() == null ? 0 : s.getDepth(),
                s.getLastItemSeq() == null ? 0L : s.getLastItemSeq(), created);
    }

    private TurnView toTurnView(RuntimeTurnDO t) {
        return new TurnView(
                t.getId(), t.getThreadId(), t.getSeq() == null ? 0 : t.getSeq(),
                TurnStatus.valueOf(t.getStatus()), t.getOrigin(), t.getOriginRef(),
                t.getStepCount() == null ? 0 : t.getStepCount(),
                t.getErrorCode(), t.getErrorMessage(),
                t.getPromptTokens(), t.getCompletionTokens(),
                toInstant(t.getCreateAt()), toInstant(t.getStartedAt()), toInstant(t.getEndedAt()));
    }

    private ItemView toItemView(RuntimeItemDO item) {
        Map<String, Object> content;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = mapper.readValue(
                    item.getContentJson() == null ? "{}" : item.getContentJson(), Map.class);
            content = c;
        } catch (Exception e) {
            content = Map.of();
        }
        return new ItemView(
                item.getId(), item.getThreadId(), item.getTurnId(),
                item.getSeq() == null ? 0L : item.getSeq(),
                item.getStep() == null ? 0 : item.getStep(),
                ItemType.fromWire(item.getType()),
                item.getRole(), content,
                item.getCreateAt() == null ? Instant.EPOCH
                        : item.getCreateAt().atZone(ZoneOffset.UTC).toInstant());
    }

    private static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, name + " 必填");
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "JSON 序列化失败");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** OBS-03：入口缺 request_id 时生成并写入关联上下文。 */
    private static RequestContext ensureCorrelation() {
        RequestContext ctx = RequestContextHolder.getCorrelation();
        if (ctx.requestId() == null || ctx.requestId().isBlank()) {
            ctx = ctx.withRequestId(Ids.newId("req"));
            RequestContextHolder.setCorrelation(ctx);
        }
        return ctx;
    }
}

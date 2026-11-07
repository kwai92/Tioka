package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.approval.ApprovalDecideService;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class KernelRuntimeService implements RuntimeApi {
    private final RuntimeSessionRepository sessions;
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

    public KernelRuntimeService(RuntimeSessionRepository sessions,
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
                                ApprovalDecideService approvalDecide) {
        this.sessions = sessions;
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
        String id = Ids.newId("thr");
        RuntimeSessionDO row = new RuntimeSessionDO();
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
        sessions.upsert(row);
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
        requireThread(threadId);
        var existing = turns.findBySessionAndClientMsgId(threadId, cmd.clientMsgId());
        if (existing.isPresent()) {
            RuntimeTurnDO t = existing.get();
            return new TurnHandle(t.getId(), TurnStatus.valueOf(t.getStatus()), eventsUrl(threadId));
        }
        int active = turns.countActive(threadId);
        if (active >= props.getMaxQueuedPerThread()) {
            throw new BizException(ErrorCode.RATE_LIMITED, "同 thread 排队超过上限");
        }
        String turnId = Ids.newId("turn");
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(turnId);
        t.setSessionId(threadId);
        t.setSeq(turns.nextSeq(threadId));
        t.setStatus(TurnStatus.QUEUED.name());
        t.setOrigin(blankToNull(cmd.origin()) == null ? "chat" : cmd.origin());
        t.setOriginRef(cmd.originRef());
        t.setClientMsgId(cmd.clientMsgId());
        t.setRunAsJson(runAsJson());
        t.setOptionsJson(optionsJson(cmd.options()));
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        try {
            turns.insert(t);
        } catch (DuplicateKeyException e) {
            RuntimeTurnDO again = turns.findBySessionAndClientMsgId(threadId, cmd.clientMsgId()).orElseThrow();
            return new TurnHandle(again.getId(), TurnStatus.valueOf(again.getStatus()), eventsUrl(threadId));
        }
        if (cmd.input() != null) {
            for (InputItem in : cmd.input()) {
                if (in == null || in.text() == null) {
                    continue;
                }
                String type = in.type() == null ? ItemType.MESSAGE.wire() : in.type();
                items.appendWithoutLease(threadId, turnId, 0, type, "user",
                        json(Map.of("text", in.text())));
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
        requireThread(t.getSessionId());
        signals.interrupt(turnId);
    }

    @Override
    public void steer(String turnId, String text) {
        requireText(turnId, "turnId");
        RuntimeTurnDO t = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        requireThread(t.getSessionId());
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
        requireThread(t.getSessionId());
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

    private RuntimeSessionDO requireThread(String threadId) {
        RuntimeSessionDO s = sessions.find(threadId);
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

    private String runAsJson() {
        Principal p = principals.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", p == null ? "0" : p.userId());
        m.put("workspaceId", p == null ? null : p.workspaceId());
        m.put("type", p == null ? "SYSTEM" : p.type().name());
        return json(m);
    }

    private String optionsJson(TurnOptions options) {
        if (options == null) {
            return null;
        }
        int max = options.maxSteps() == null ? props.getLoop().getMaxSteps() : Math.min(50, options.maxSteps());
        return json(Map.of(
                "maxSteps", max,
                "approvalPolicy", options.approvalPolicy() == null ? "" : options.approvalPolicy(),
                "toolAllowList", options.toolAllowList() == null ? List.of() : options.toolAllowList()));
    }

    private String eventsUrl(String threadId) {
        return "/api/runtime/threads/" + threadId + "/events";
    }

    private ThreadView toThreadView(RuntimeSessionDO s) {
        Instant created = s.getCreateAt() == null ? Instant.EPOCH
                : s.getCreateAt().atZone(ZoneOffset.UTC).toInstant();
        return new ThreadView(s.getId(), s.getAgentVersionId(), s.getOrigin(), s.getOriginRef(),
                s.getParentTurnId(), s.getDepth() == null ? 0 : s.getDepth(),
                s.getLastItemSeq() == null ? 0L : s.getLastItemSeq(), created);
    }

    private TurnView toTurnView(RuntimeTurnDO t) {
        return new TurnView(
                t.getId(), t.getSessionId(), t.getSeq() == null ? 0 : t.getSeq(),
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
                item.getId(), item.getSessionId(), item.getTurnId(),
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

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "JSON 序列化失败");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, name + " 必填");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

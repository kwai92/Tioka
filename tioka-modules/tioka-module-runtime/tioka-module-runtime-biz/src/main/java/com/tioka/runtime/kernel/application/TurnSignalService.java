package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.approval.ApprovalDecideService;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RT-05：interrupt / steer 控制面（供 KernelRuntimeService 与测试直接调用）。
 */
@Service
public class TurnSignalService {
    private static final int STEER_MAX = 4000;

    private final TurnRepository turns;
    private final ItemRepository items;
    private final SignalBus signals;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectMapper mapper;
    private final ApprovalRepository approvals;
    private final ApprovalDecideService approvalDecide;

    public TurnSignalService(TurnRepository turns,
                             ItemRepository items,
                             SignalBus signals,
                             EventStream streams,
                             RuntimeEventFactory events,
                             ObjectMapper mapper,
                             ApprovalRepository approvals,
                             ApprovalDecideService approvalDecide) {
        this.turns = turns;
        this.items = items;
        this.signals = signals;
        this.streams = streams;
        this.events = events;
        this.mapper = mapper;
        this.approvals = approvals;
        this.approvalDecide = approvalDecide;
    }

    /** FND-04e：身份失效取消原因，写入 {@code runtime_turn.error_code}。 */
    public static final String REASON_PRINCIPAL_REVOKED = "principal_revoked";

    public void interrupt(String turnId) {
        interrupt(turnId, null);
    }

    /**
     * @param reason 非空时写入 error_code（如 {@link #REASON_PRINCIPAL_REVOKED}）
     */
    public void interrupt(String turnId, String reason) {
        // 与 acquire / 状态迁移竞态：cancelQueued 等 CAS 失败时重读再处理，禁止静默 return
        for (int attempt = 0; attempt < 3; attempt++) {
            RuntimeTurnDO turn = turns.findById(turnId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
            TurnStatus st = TurnStatus.valueOf(turn.getStatus());
            if (st.isTerminal()) {
                return;
            }
            if (st == TurnStatus.QUEUED) {
                int n = turns.cancelQueued(turnId, reason, null);
                if (n > 0) {
                    String payload = events.turnStatus(
                            com.tioka.runtime.api.kernel.RuntimeEventType.TURN_CANCELLED,
                            turn.getThreadId(), turnId, TurnStatus.CANCELLED.name());
                    streams.publish(turn.getThreadId(), null,
                            com.tioka.runtime.api.kernel.RuntimeEventType.TURN_CANCELLED.wire(), payload);
                    return;
                }
                continue;
            }
            if (st == TurnStatus.WAITING_APPROVAL) {
                // RT-07：WAITING 时无 worker，直接 CANCELLED + PENDING 审批 EXPIRED
                int n = turns.cancelWaitingApproval(turnId, reason, null);
                if (n > 0) {
                    List<RuntimeApprovalDO> pending = approvals.listBySubject(
                                    ApprovalSubjectType.TURN.wire(), turnId).stream()
                            .filter(a -> "PENDING".equals(a.getStatus()))
                            .toList();
                    approvals.expirePendingForSubject(ApprovalSubjectType.TURN.wire(), turnId, "system");
                    for (RuntimeApprovalDO a : pending) {
                        a.setStatus("EXPIRED");
                        a.setDecidedBy("system");
                        approvalDecide.notifyDecided(a);
                    }
                    String payload = events.turnStatus(RuntimeEventType.TURN_CANCELLED,
                            turn.getThreadId(), turnId, TurnStatus.CANCELLED.name());
                    streams.publish(turn.getThreadId(), null, RuntimeEventType.TURN_CANCELLED.wire(), payload);
                    return;
                }
                continue;
            }
            if (st == TurnStatus.RUNNING
                    || st == TurnStatus.WAITING_CHILD
                    || st == TurnStatus.INTERRUPTED) {
                if (reason == null || reason.isBlank()) {
                    signals.publishCancel(turnId);
                } else {
                    signals.publishCancel(turnId, reason);
                }
                return;
            }
            throw new BizException(ErrorCode.CONFLICT, "当前状态不可取消: " + st);
        }
        // 极端：连续 CAS 失败后仍非终态 — 以当前态再投递一次信号或放弃
        RuntimeTurnDO last = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        TurnStatus st = TurnStatus.valueOf(last.getStatus());
        if (st.isTerminal()) {
            return;
        }
        if (st == TurnStatus.RUNNING || st == TurnStatus.WAITING_CHILD || st == TurnStatus.INTERRUPTED) {
            if (reason == null || reason.isBlank()) {
                signals.publishCancel(turnId);
            } else {
                signals.publishCancel(turnId, reason);
            }
            return;
        }
        throw new BizException(ErrorCode.CONFLICT, "当前状态不可取消: " + st);
    }

    public void steer(String turnId, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "插话文本为空");
        }
        if (text.length() > STEER_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "插话超过 " + STEER_MAX + " 字符");
        }
        RuntimeTurnDO turn = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        TurnStatus st = TurnStatus.valueOf(turn.getStatus());
        if (st == TurnStatus.QUEUED) {
            // 与 interrupt 竞态：写前再确认仍为 QUEUED，否则改走 RUNNING 信号或 CONFLICT
            turn = turns.findById(turnId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
            st = TurnStatus.valueOf(turn.getStatus());
            if (st == TurnStatus.QUEUED) {
                appendSteerItem(turn, text);
                return;
            }
        }
        if (st == TurnStatus.RUNNING) {
            signals.publishSteer(turnId, text);
            return;
        }
        throw new BizException(ErrorCode.CONFLICT, "当前状态不可插话: " + st);
    }

    private void appendSteerItem(RuntimeTurnDO turn, String text) {
        try {
            String content = mapper.writeValueAsString(Map.of("text", text, "steer", true));
            RuntimeItemDO item = items.appendWithoutLease(ItemWrite.withoutLease(
                    turn.getThreadId(), turn.getId(), 0, ItemType.MESSAGE.wire(), "user", content));
            streams.publish(turn.getThreadId(), EventId.of(item.getSeq()).toString(),
                    com.tioka.runtime.api.kernel.RuntimeEventType.ITEM_COMPLETED.wire(),
                    events.itemCompleted(turn.getThreadId(), item));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "插话写入失败");
        }
    }
}

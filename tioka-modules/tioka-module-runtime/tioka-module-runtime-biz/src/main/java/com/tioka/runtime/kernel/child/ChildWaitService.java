package com.tioka.runtime.kernel.child;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.kernel.WaitReason;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 异步工具占位后挂起 Turn → {@link TurnStatus#WAITING_CHILD}（RT-09）。
 */
@Service
public class ChildWaitService {
    private final TurnRepository turns;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ChildPendingRegistry registry;

    public ChildWaitService(TurnRepository turns,
                            EventStream streams,
                            RuntimeEventFactory events,
                            ChildPendingRegistry registry) {
        this.turns = turns;
        this.streams = streams;
        this.events = events;
        this.registry = registry;
    }

    @Transactional
    public void suspend(LoopContext ctx, List<LoopPorts.ToolOutcome> pendings) {
        if (pendings == null || pendings.isEmpty()) {
            return;
        }
        for (LoopPorts.ToolOutcome o : pendings) {
            if (o.pending() != null && o.pending().jobId() != null) {
                registry.register(o.pending().jobId(), ctx.turnId(), o.callId());
            }
        }
        int n = turns.markWaitingChild(ctx.turnId(), ctx.leaseVersion());
        if (n == 0) {
            throw new BizException(ErrorCode.LEASE_LOST, "挂起 WAITING_CHILD 时租约丢失");
        }
        streams.publish(ctx.threadId(), null, RuntimeEventType.TURN_WAITING.wire(),
                events.turnStatus(RuntimeEventType.TURN_WAITING, ctx.threadId(), ctx.turnId(),
                        TurnStatus.WAITING_CHILD.name(), WaitReason.BACKGROUND_JOB.name()));
    }
}

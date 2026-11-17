package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.foundation.common.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.HumanTaskRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SCH-09 / ADR-066 §7.4：结构化产出自动链路失败转人工补录。
 *
 * <p>事务内插入 {@code runtime_human_task}、写 {@code data.complete} card item、Turn → WAITING_APPROVAL
 * （复用 {@link ApprovalSuspendService} 的挂起范式，{@link com.tioka.runtime.kernel.TurnRunner}
 * 见 WAITING_APPROVAL 即释放锁与配额、不写终态）。
 */
@Service
public class HumanTaskSuspendService implements LoopPorts.HumanTaskPort {

    /** 补录超时缺省 3 个工作日（ADR-066 §7.4）；工作日历由 Foundation BAS-18 提供，此处按 72 小时近似。 */
    private final Duration timeout;
    private final HumanTaskRepository humanTasks;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public HumanTaskSuspendService(HumanTaskRepository humanTasks,
                                   TurnRepository turns,
                                   ItemRepository items,
                                   EventStream streams,
                                   RuntimeEventFactory events,
                                   PrincipalHolder principals,
                                   ObjectMapper mapper,
                                   @Value("${tioka.runtime.human-task-timeout-hours:72}") long timeoutHours) {
        this.humanTasks = humanTasks;
        this.turns = turns;
        this.items = items;
        this.streams = streams;
        this.events = events;
        this.principals = principals;
        this.mapper = mapper;
        this.timeout = Duration.ofHours(timeoutHours <= 0 ? 72 : timeoutHours);
    }

    @Override
    @Transactional
    public String create(LoopContext ctx, int step, String schemaRef, String candidateJson,
                         List<String> pendingPointers) {
        String by = principals.current() == null ? "system" : principals.current().userId();
        String taskId = Ids.newId("htk");
        Instant expires = Instant.now().plus(timeout);

        RuntimeHumanTaskDO row = new RuntimeHumanTaskDO();
        row.setId(taskId);
        row.setWorkspaceId(ctx.workspaceId());
        row.setSubjectType(ApprovalSubjectType.TURN.wire());
        row.setSubjectId(ctx.turnId());
        row.setThreadId(ctx.threadId());
        row.setTurnId(ctx.turnId());
        row.setKind("data.complete");
        row.setSchemaRef(schemaRef);
        row.setCandidateJson(candidateJson);
        row.setPendingPointers(json(pendingPointers == null ? List.of() : pendingPointers));
        row.setStatus("PENDING");
        row.setExpiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
        row.setCreateBy(by);
        row.setUpdateBy(by);
        humanTasks.insert(row);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kind", "data.complete");
        card.put("humanTaskId", taskId);
        card.put("schemaRef", schemaRef);
        card.put("pendingPointers", pendingPointers == null ? List.of() : pendingPointers);
        card.put("candidate", candidateJson == null ? null : readTree(candidateJson));
        RuntimeItemDO cardItem = items.append(ItemWrite.append(ctx.threadId(), ctx.turnId(),
                ctx.leaseVersion(), step, ItemType.CARD.wire(), "system", json(card), null));
        row.setItemId(cardItem.getId());
        streams.publish(ctx.threadId(), EventId.of(cardItem.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(ctx.threadId(), cardItem));

        int n = turns.markWaitingApproval(ctx.turnId(), ctx.leaseVersion());
        if (n == 0) {
            throw new BizException(ErrorCode.LEASE_LOST, "生成人工补录任务时租约丢失");
        }
        streams.publish(ctx.threadId(), null, RuntimeEventType.TURN_WAITING.wire(),
                events.turnStatus(RuntimeEventType.TURN_WAITING, ctx.threadId(), ctx.turnId(),
                        TurnStatus.WAITING_APPROVAL.name()));
        return taskId;
    }

    @Override
    public LoopPorts.HumanTaskPort.SubmittedCompletion takeSubmitted(LoopContext ctx) {
        return humanTasks.findLatestSubmitted(ApprovalSubjectType.TURN.wire(), ctx.turnId())
                .map(t -> new LoopPorts.HumanTaskPort.SubmittedCompletion(
                        t.getId(), t.getSchemaRef(), t.getAnswerJson(), t.getDecidedBy()))
                .orElse(null);
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}

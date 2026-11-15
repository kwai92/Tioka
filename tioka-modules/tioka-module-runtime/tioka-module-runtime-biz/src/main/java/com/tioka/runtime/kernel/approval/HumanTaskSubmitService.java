package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import com.tioka.runtime.kernel.infrastructure.HumanTaskRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * SCH-09 / ADR-066 §7.4：人工补录提交（审批台 {@code data.complete} 任务）。
 *
 * <p>人提交的数据经流水线第 3〜8 步校验由续跑后的
 * {@link com.tioka.runtime.kernel.application.loop.OutputStage#resumePending} 承接：
 * 本服务只负责置任务 {@code SUBMITTED} 并把原 {@code WAITING_APPROVAL} Turn 重新入队；
 * 通过则写 {@code outcome=VALID} 且 {@code producedBy.human} 的新 data item（收口），
 * 不通过则按原错误清单与候选再次生成补录任务（人也会填错）。
 */
@Service
public class HumanTaskSubmitService {

    private final HumanTaskRepository humanTasks;
    private final TurnRepository turns;
    private final TurnDispatchQueue dispatchQueue;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public HumanTaskSubmitService(HumanTaskRepository humanTasks,
                                  TurnRepository turns,
                                  TurnDispatchQueue dispatchQueue,
                                  PrincipalHolder principals,
                                  ObjectMapper mapper) {
        this.humanTasks = humanTasks;
        this.turns = turns;
        this.dispatchQueue = dispatchQueue;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Transactional
    public void submit(String taskId, Map<String, Object> answer) {
        Principal p = principals.current();
        if (p == null || !p.hasPermission(Permissions.APPROVAL_DECIDE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少 approval:decide");
        }
        RuntimeHumanTaskDO t = humanTasks.findForUpdate(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "补录任务不存在"));
        String status = t.getStatus();
        if ("SUBMITTED".equals(status)) {
            return; // 同结果幂等
        }
        if (!"PENDING".equals(status)) {
            throw new BizException(ErrorCode.CONFLICT, "补录任务已 " + status);
        }
        String answerJson = json(answer == null ? Map.of() : answer);
        int n = humanTasks.submit(taskId, p.userId(), answerJson);
        if (n == 0) {
            throw new BizException(ErrorCode.CONFLICT, "补录任务已被处理");
        }
        if (ApprovalSubjectType.TURN.wire().equals(t.getSubjectType())) {
            int r = turns.requeueFromWaitingApproval(t.getTurnId());
            if (r > 0) {
                afterCommit(() -> dispatchQueue.offer(t.getTurnId(), t.getThreadId()));
            }
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}

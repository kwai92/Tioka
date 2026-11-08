package com.tioka.orchestration.attention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.orchestration.application.decide.OrchStateMachine;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import com.tioka.runtime.api.approval.ApprovalSubjectHandler;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * orch_run 审批续跑（ORC-04）：APPROVED/REJECTED → 同 thread 新 Turn；EXPIRED 用默认答案或失败。
 */
@Component
public class OrchApprovalHandler implements ApprovalSubjectHandler {

    private static final Logger log = LoggerFactory.getLogger(OrchApprovalHandler.class);

    private final OrchEngineRepository repo;
    /** 懒取：避免 Handler → RuntimeApi → ApprovalRouter → Handler 环。 */
    private final ObjectProvider<RuntimeApi> runtime;
    private final PrincipalLoaderApi principalLoader;
    private final ObjectMapper mapper;

    public OrchApprovalHandler(OrchEngineRepository repo,
                               ObjectProvider<RuntimeApi> runtime,
                               PrincipalLoaderApi principalLoader,
                               ObjectMapper mapper) {
        this.repo = repo;
        this.runtime = runtime;
        this.principalLoader = principalLoader;
        this.mapper = mapper;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectType.ORCH_RUN.wire();
    }

    @Override
    @Transactional
    public void onDecided(ApprovalView approval) {
        if (approval == null || approval.subjectId() == null) {
            return;
        }
        OrchRunDO run = repo.lockRun(approval.subjectId());
        if (run == null) {
            return;
        }
        OrchApi.RunStatus status;
        try {
            status = OrchApi.RunStatus.valueOf(run.getStatus());
        } catch (Exception e) {
            return;
        }
        if (status != OrchApi.RunStatus.WAITING_ATTENTION) {
            return;
        }

        OrchWorkItemDO wi = repo.findWorkItem(run.getWorkItemId());
        OrchMandateDO mandate = wi == null ? null : repo.findLatestMandate(wi.getId());
        OrchRuleVersionDO version = run.getRuleVersionId() == null ? null : repo.findVersion(run.getRuleVersionId());
        if (!mandateOk(mandate, version, run.getWorkspaceId())) {
            fail(run, wi, "MANDATE_INVALID");
            return;
        }

        ApprovalStatus decided = approval.status();
        if (decided == ApprovalStatus.EXPIRED) {
            Map<String, Object> defaults = approval.card() == null ? Map.of()
                    : (approval.card().get("onTimeout") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of());
            if (!defaults.isEmpty()) {
                resume(run, wi, approval.id(), defaults, "超时默认答复");
                return;
            }
            // escalate 配置（规则级）本包最小实现：无默认答案则失败
            fail(run, wi, "ATTENTION_TIMEOUT");
            return;
        }
        if (decided != ApprovalStatus.APPROVED && decided != ApprovalStatus.REJECTED) {
            return;
        }
        Map<String, Object> answer = approval.answer() == null ? Map.of() : approval.answer();
        String comment = approval.comment() == null ? "" : approval.comment();
        resume(run, wi, approval.id(), answer, comment);
    }

    private void resume(OrchRunDO run, OrchWorkItemDO wi, String approvalId,
                        Map<String, Object> answer, String comment) {
        if (!OrchStateMachine.canTransit(OrchApi.RunStatus.WAITING_ATTENTION, OrchApi.RunStatus.RUNNING)) {
            return;
        }
        String threadId = run.getThreadId();
        if ((threadId == null || threadId.isBlank()) && wi != null) {
            threadId = wi.getThreadId();
        }
        if (threadId == null || threadId.isBlank()) {
            fail(run, wi, "THREAD_MISSING");
            return;
        }
        String answerJson;
        try {
            answerJson = mapper.writeValueAsString(answer);
        } catch (Exception e) {
            answerJson = String.valueOf(answer);
        }
        String prompt = "人工答复：" + answerJson + "，备注：" + (comment == null ? "" : comment);
        RuntimeApi.TurnHandle handle = runtime.getObject().startTurn(threadId, new RuntimeApi.StartTurnCmd(
                approvalId,
                List.of(RuntimeApi.InputItem.text(prompt)),
                new RuntimeApi.TurnOptions(20, null, List.of(OrchTools.RECEIPT)),
                "orch",
                run.getId()));
        run.setStatus(OrchApi.RunStatus.RUNNING.name());
        run.setThreadId(threadId);
        run.setCurrentTurnId(handle.turnId());
        run.setEndedAt(null);
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        repo.appendFact(run.getId(), "attention_answered", json(Map.of(
                "approvalId", approvalId,
                "turnId", handle.turnId())));
    }

    private void fail(OrchRunDO run, OrchWorkItemDO wi, String code) {
        run.setStatus(OrchApi.RunStatus.FAILED.name());
        run.setErrorCode(code);
        run.setErrorMessage(code);
        run.setEndedAt(LocalDateTime.now());
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        if (wi != null) {
            wi.setActiveRunId(null);
            wi.setUpdateAt(LocalDateTime.now());
            repo.updateWorkItem(wi);
        }
        repo.appendFact(run.getId(), "attention_failed", json(Map.of("code", code)));
    }

    private boolean mandateOk(OrchMandateDO mandate, OrchRuleVersionDO version, String workspaceId) {
        if (mandate == null || !"ACTIVE".equals(mandate.getStatus())) {
            return false;
        }
        if (version == null || "RETIRED".equals(version.getStage())) {
            return false;
        }
        try {
            principalLoader.load(Long.parseLong(mandate.getServiceAccountId()), workspaceId);
            return true;
        } catch (Exception e) {
            log.debug("Mandate 复验失败: {}", e.toString());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}

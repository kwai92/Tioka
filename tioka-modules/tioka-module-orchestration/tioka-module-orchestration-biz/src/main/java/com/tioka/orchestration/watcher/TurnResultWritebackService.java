package com.tioka.orchestration.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.orchestration.api.watcher.TurnFinishedMessage;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.receipt.OrchReceiptRepository;
import com.tioka.orchestration.receipt.ReceiptApplyCommand;
import com.tioka.orchestration.receipt.ReceiptApplyService;
import com.tioka.orchestration.application.pause.RulePauseService;
import com.tioka.orchestration.receipt.ReceiptFactKinds;
import com.tioka.orchestration.receipt.ReceiptLimits;
import com.tioka.orchestration.receipt.ReceiptValidationResult;
import com.tioka.orchestration.receipt.ReceiptValidator;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turn 终态 → Run 回写（M2-02「Turn 结果回写」）。
 */
@Service
public class TurnResultWritebackService {

    private static final Logger log = LoggerFactory.getLogger(TurnResultWritebackService.class);

    private static final Set<String> TERMINAL = Set.of(
            OrchApi.RunStatus.SUCCEEDED.name(),
            OrchApi.RunStatus.FAILED.name(),
            OrchApi.RunStatus.CANCELLED.name(),
            OrchApi.RunStatus.UNCERTAIN.name());

    private final OrchReceiptRepository repo;
    private final ReceiptExtractor extractor;
    private final ReceiptValidator validator;
    private final ReceiptApplyService applyService;
    private final RuntimeApi runtime;
    private final List<ToolProvider> toolProviders;
    private final ObjectMapper mapper;
    private final RulePauseService rulePause;

    public TurnResultWritebackService(OrchReceiptRepository repo,
                                      ReceiptExtractor extractor,
                                      ReceiptValidator validator,
                                      ReceiptApplyService applyService,
                                      RuntimeApi runtime,
                                      List<ToolProvider> toolProviders,
                                      ObjectMapper mapper,
                                      RulePauseService rulePause) {
        this.repo = repo;
        this.extractor = extractor;
        this.validator = validator;
        this.applyService = applyService;
        this.runtime = runtime;
        this.toolProviders = toolProviders;
        this.mapper = mapper;
        this.rulePause = rulePause;
    }

    @Transactional
    public void onTurnFinished(TurnFinishedMessage msg) {
        if (msg == null || !msg.isOrchOrigin()) {
            return;
        }
        String runId = msg.originRef();
        Optional<OrchRunDO> opt = repo.findRun(runId);
        if (opt.isEmpty()) {
            log.debug("orch-turn-watcher: run 不存在 runId={}", runId);
            return;
        }
        OrchRunDO run = opt.get();
        if (TERMINAL.contains(run.getStatus())) {
            repo.appendFact(run.getId(), ReceiptFactKinds.LATE_RESULT, Map.of(
                    "turnId", msg.turnId(),
                    "status", msg.status()));
            return;
        }

        String status = msg.status() == null ? "" : msg.status();
        if (TurnStatus.CANCELLED.name().equals(status)) {
            run.setStatus(OrchApi.RunStatus.CANCELLED.name());
            run.setErrorCode(msg.errorCode());
            repo.updateRun(run);
            repo.appendFact(run.getId(), ReceiptFactKinds.TURN_CANCELLED, Map.of("turnId", msg.turnId()));
            repo.clearActiveRun(run.getWorkItemId(), run.getId());
            return;
        }

        if (TurnStatus.FAILED.name().equals(status)) {
            handleFailed(run, msg);
            return;
        }

        if (!TurnStatus.COMPLETED.name().equals(status)) {
            log.debug("orch-turn-watcher: 忽略非终态 status={}", status);
            return;
        }

        handleCompleted(run, msg);
    }

    private void handleCompleted(OrchRunDO run, TurnFinishedMessage msg) {
        if (OrchApi.RunStatus.DISPATCHED.name().equals(run.getStatus())) {
            run.setStatus(OrchApi.RunStatus.RUNNING.name());
            repo.updateRun(run);
        }

        Optional<JsonNode> raw = extractor.extract(msg.threadId(), msg.turnId());
        if (raw.isEmpty()) {
            applyService.applyMissingReceipt(run);
            return;
        }

        OrchRuleVersionDO version = repo.findVersion(run.getRuleVersionId()).orElse(null);
        Set<String> whitelist = ReceiptValidator.parseEmitsJson(
                version == null ? null : version.getEmitsJson(), mapper);
        String tell = version == null ? "needed" : version.getTell();

        ReceiptValidationResult validated = validator.validate(raw.get(), whitelist);
        if (!validated.ok()) {
            rewriteOrExhaust(run, msg, validated.errors());
            return;
        }

        applyService.apply(new ReceiptApplyCommand(
                run, validated.receipt(), validated.rejected(), tell));
    }

    private void rewriteOrExhaust(OrchRunDO run, TurnFinishedMessage msg, List<String> errors) {
        int rewriteCount = run.getRewriteCount() == null ? 0 : run.getRewriteCount();
        if (rewriteCount < ReceiptLimits.MAX_REWRITE_COUNT) {
            run.setRewriteCount(rewriteCount + 1);
            run.setStatus(OrchApi.RunStatus.RUNNING.name());
            String clientMsgId = run.getId() + ":rewrite:" + run.getRewriteCount();
            String prompt = "回执不合法：" + String.join("; ", errors) + "，请重新调用 orch.receipt";
            RuntimeApi.TurnHandle handle = runtime.startTurn(run.getThreadId(), new RuntimeApi.StartTurnCmd(
                    clientMsgId,
                    List.of(RuntimeApi.InputItem.text(prompt)),
                    new RuntimeApi.TurnOptions(20, null, List.of(OrchTools.RECEIPT)),
                    TurnFinishedMessage.ORCH_ORIGIN,
                    run.getId()));
            run.setCurrentTurnId(handle.turnId());
            repo.updateRun(run);
            repo.appendFact(run.getId(), ReceiptFactKinds.RECEIPT_REWRITE, Map.of(
                    "errors", errors,
                    "rewriteCount", run.getRewriteCount(),
                    "newTurnId", handle.turnId()));
            return;
        }
        applyService.applyInvalidExhausted(run, errors, msg.status());
    }

    private void handleFailed(OrchRunDO run, TurnFinishedMessage msg) {
        if (hasSideEffectTools(msg.threadId(), msg.turnId())) {
            run.setStatus(OrchApi.RunStatus.UNCERTAIN.name());
            run.setErrorCode(msg.errorCode() == null ? "TURN_FAILED" : msg.errorCode());
            repo.updateRun(run);
            repo.appendFact(run.getId(), ReceiptFactKinds.TURN_FAILED, Map.of(
                    "uncertain", true,
                    "turnId", msg.turnId()));
            return;
        }
        int attempt = run.getAttempt() == null ? 1 : run.getAttempt();
        if (attempt < ReceiptLimits.MAX_ATTEMPT) {
            run.setAttempt(attempt + 1);
            run.setStatus(OrchApi.RunStatus.RUNNING.name());
            String clientMsgId = run.getId() + ":" + run.getAttempt();
            RuntimeApi.TurnHandle handle = runtime.startTurn(run.getThreadId(), new RuntimeApi.StartTurnCmd(
                    clientMsgId,
                    List.of(RuntimeApi.InputItem.text("上一 Turn 失败，请重试并调用 orch.receipt")),
                    new RuntimeApi.TurnOptions(20, null, List.of(OrchTools.RECEIPT)),
                    TurnFinishedMessage.ORCH_ORIGIN,
                    run.getId()));
            run.setCurrentTurnId(handle.turnId());
            repo.updateRun(run);
            repo.appendFact(run.getId(), ReceiptFactKinds.TURN_FAILED, Map.of(
                    "retry", true,
                    "attempt", run.getAttempt(),
                    "newTurnId", handle.turnId()));
            return;
        }
        run.setStatus(OrchApi.RunStatus.FAILED.name());
        run.setErrorCode(msg.errorCode() == null ? "TURN_FAILED" : msg.errorCode());
        repo.updateRun(run);
        // ORC-07 自动暂停：Turn 重试耗尽 → Run FAILED，计入规则连续失败
        rulePause.recordRunOutcome(run.getRuleVersionId(), OrchApi.RunStatus.FAILED.name());
        repo.appendFact(run.getId(), ReceiptFactKinds.TURN_FAILED, Map.of(
                "exhausted", true,
                "turnId", msg.turnId()));
        repo.clearActiveRun(run.getWorkItemId(), run.getId());
    }

    private boolean hasSideEffectTools(String threadId, String turnId) {
        List<RuntimeApi.ItemView> items = runtime.listItems(threadId, 0, 500);
        for (RuntimeApi.ItemView item : items) {
            if (!turnId.equals(item.turnId()) || item.type() != ItemType.TOOL_CALL) {
                continue;
            }
            Object name = item.content() == null ? null : item.content().get("name");
            if (name == null || OrchTools.RECEIPT.equals(String.valueOf(name))) {
                continue;
            }
            ToolRisk risk = resolveRisk(String.valueOf(name));
            if (risk == ToolRisk.WRITE || risk == ToolRisk.EXTERNAL_SEND || risk == ToolRisk.DESTRUCTIVE) {
                return true;
            }
        }
        return false;
    }

    private ToolRisk resolveRisk(String toolName) {
        for (ToolProvider p : toolProviders) {
            for (ToolProvider.ToolDescriptor d : p.listTools(null)) {
                if (toolName.equals(d.name())) {
                    return d.risk();
                }
            }
        }
        return ToolRisk.READ;
    }
}

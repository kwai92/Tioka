package com.tioka.orchestration.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.mq.ConnectorMqTopics;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.api.engine.Receipt;
import com.tioka.orchestration.api.mq.OrchestrationMqTopics;
import com.tioka.orchestration.attention.OrchAttentionService;
import com.tioka.orchestration.application.pause.RulePauseService;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回执同事务生效：notice / ask / later / emit + Run 终态（M2-02）。
 */
@Service
public class ReceiptApplyService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptApplyService.class);

    private static final String TELL_NEVER = "never";
    private static final String TELL_ALWAYS = "always";
    private static final String TELL_NEEDED = "needed";

    private final OrchReceiptRepository repo;
    private final EventPublisher publisher;
    private final OrchApi orchApi;
    private final ObjectMapper mapper;
    private final OrchAttentionService attention;
    private final RulePauseService rulePause;

    public ReceiptApplyService(OrchReceiptRepository repo,
                               EventPublisher publisher,
                               OrchApi orchApi,
                               ObjectMapper mapper,
                               OrchAttentionService attention,
                               RulePauseService rulePause) {
        this.repo = repo;
        this.publisher = publisher;
        this.orchApi = orchApi;
        this.mapper = mapper;
        this.attention = attention;
        this.rulePause = rulePause;
    }

    public void apply(ReceiptApplyCommand cmd) {
        OrchRunDO run = cmd.run();
        Receipt receipt = cmd.receipt();

        for (ReceiptValidationResult.RejectedEmit r : cmd.rejected()) {
            repo.appendFact(run.getId(), ReceiptFactKinds.EMIT_REJECTED, Map.of(
                    "type", r.type(),
                    "reason", r.reason()));
        }

        boolean hasAsk = hasAskPending(receipt);
        int emitSeq = 0;
        for (Receipt.Action action : receipt.actions()) {
            if (action instanceof Receipt.Notice notice) {
                maybeSendNotice(run, receipt, notice, cmd.tellPolicy(), hasAsk);
            } else if (action instanceof Receipt.Ask ask) {
                attention.openFromAsk(run, ask);
            } else if (action instanceof Receipt.Later later) {
                applyLater(run, later);
            } else if (action instanceof Receipt.Emit emit) {
                applyEmit(run, emit, emitSeq++);
            }
        }

        try {
            run.setReceiptJson(mapper.writeValueAsString(receipt));
        } catch (Exception e) {
            throw new IllegalStateException("回执序列化失败", e);
        }

        if (hasAsk) {
            run.setStatus(OrchApi.RunStatus.WAITING_ATTENTION.name());
            run.setEndedAt(null);
            repo.updateRun(run);
            return;
        }

        OrchApi.RunStatus terminal = switch (receipt.outcome()) {
            case DONE, NOTHING -> OrchApi.RunStatus.SUCCEEDED;
            case FAILED -> OrchApi.RunStatus.FAILED;
            case CANCELLED -> OrchApi.RunStatus.CANCELLED;
        };
        run.setStatus(terminal.name());
        run.setEndedAt(LocalDateTime.now());
        if (terminal == OrchApi.RunStatus.FAILED) {
            run.setErrorCode("RECEIPT_FAILED");
            run.setErrorMessage(receipt.summary());
        }
        repo.updateRun(run);
        repo.appendFact(run.getId(), ReceiptFactKinds.RECEIPT_APPLIED, Map.of(
                "outcome", receipt.outcome().name()));
        repo.clearActiveRun(run.getWorkItemId(), run.getId());
        publishRunFinished(run, terminal);
    }

    /** 无回执时按 resolve/DONE 收口。 */
    public void applyMissingReceipt(OrchRunDO run) {
        Receipt synthetic = new Receipt(Receipt.Outcome.DONE, "missing receipt treated as resolve", Map.of(), List.of());
        try {
            run.setReceiptJson(mapper.writeValueAsString(synthetic));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        run.setStatus(OrchApi.RunStatus.SUCCEEDED.name());
        run.setEndedAt(LocalDateTime.now());
        repo.updateRun(run);
        repo.appendFact(run.getId(), ReceiptFactKinds.MISSING_RECEIPT, Map.of());
        repo.clearActiveRun(run.getWorkItemId(), run.getId());
        publishRunFinished(run, OrchApi.RunStatus.SUCCEEDED);
    }

    /** 回执作废但仍按 Turn 业务终态收口。 */
    public void applyInvalidExhausted(OrchRunDO run, List<String> errors, String turnStatus) {
        run.setReceiptJson(null);
        OrchApi.RunStatus terminal = "FAILED".equals(turnStatus)
                ? OrchApi.RunStatus.FAILED
                : "CANCELLED".equals(turnStatus)
                ? OrchApi.RunStatus.CANCELLED
                : OrchApi.RunStatus.SUCCEEDED;
        run.setStatus(terminal.name());
        run.setEndedAt(LocalDateTime.now());
        if (terminal == OrchApi.RunStatus.FAILED) {
            run.setErrorCode("RECEIPT_INVALID");
            run.setErrorMessage(String.join("; ", errors));
        }
        repo.updateRun(run);
        repo.appendFact(run.getId(), ReceiptFactKinds.RECEIPT_INVALID, Map.of(
                "errors", errors,
                "turnStatus", turnStatus));
        repo.clearActiveRun(run.getWorkItemId(), run.getId());
        publishRunFinished(run, terminal);
        // 通知卡片：说明回执作废
        publishImNotice(run, "回执校验失败且重写次数已用尽：" + String.join("; ", errors));
    }

    private static boolean hasAskPending(Receipt receipt) {
        return receipt.actions().stream().anyMatch(a -> a instanceof Receipt.Ask);
    }

    private void maybeSendNotice(OrchRunDO run, Receipt receipt, Receipt.Notice notice,
                                 String tellPolicy, boolean hasAsk) {
        String tell = tellPolicy == null || tellPolicy.isBlank() ? TELL_NEEDED : tellPolicy;
        if (TELL_NEVER.equals(tell)) {
            return;
        }
        boolean needed = receipt.outcome() == Receipt.Outcome.FAILED
                || hasAsk
                || (receipt.summary() != null && !receipt.summary().isBlank());
        if (TELL_NEEDED.equals(tell) && !needed) {
            return;
        }
        if (!TELL_ALWAYS.equals(tell) && !TELL_NEEDED.equals(tell)) {
            return;
        }
        publishImNotice(run, notice.text());
    }

    private void applyLater(OrchRunDO run, Receipt.Later later) {
        LocalDateTime fireAt = later.at().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        repo.upsertLaterTimer(new OrchReceiptRepository.UpsertLaterCommand(
                run.getWorkspaceId(),
                run.getWorkItemId(),
                later.key(),
                fireAt,
                Map.of(
                        "input", later.input() == null ? "" : later.input(),
                        "runId", run.getId(),
                        "at", later.at().toString())));
    }

    private void applyEmit(OrchRunDO run, Receipt.Emit emit, int seq) {
        String idem = run.getId() + ":" + seq;
        OrchApi.EventEnvelope event = new OrchApi.EventEnvelope(
                "orch",
                emit.type(),
                idem,
                emit.subjectKey(),
                emit.attrs(),
                emit.body(),
                1);
        try {
            orchApi.ingest(event);
        } catch (BizException e) {
            if (e.getErrorCode() != ErrorCode.NOT_IMPLEMENTED) {
                throw e;
            }
            log.info("OrchApi.ingest 尚未实现，emit 记 fact 待 PK-12：type={}", emit.type());
            repo.appendFact(run.getId(), "emit_deferred", Map.of(
                    "type", emit.type(),
                    "idempotencyKey", idem,
                    "reason", "OrchApi.ingest pending PK-12"));
        }
    }

    private void publishImNotice(OrchRunDO run, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingId", "orch-default");
        payload.put("channel", "feishu");
        payload.put("target", Map.of("to", "requester"));
        payload.put("message", Map.of("text", text));
        payload.put("purpose", "orch.notice");
        payload.put("refType", "orch_run");
        payload.put("refId", run.getId());
        payload.put("workspaceId", run.getWorkspaceId());
        publisher.publish(new DomainEvent(
                ConnectorMqTopics.IM_SEND_REQUESTED,
                ConnectorMqTopics.TOPIC,
                run.getId(),
                payload,
                0));
    }

    private void publishRunFinished(OrchRunDO run, OrchApi.RunStatus status) {
        // ORC-07 自动暂停：Run 终态驱动规则失败计数（SUCCEEDED 清零 / FAILED +1 达阈值暂停）
        rulePause.recordRunOutcome(run.getRuleVersionId(), status.name());
        publisher.publish(new DomainEvent(
                OrchestrationMqTopics.RUN_FINISHED,
                OrchestrationMqTopics.TOPIC,
                run.getId(),
                Map.of(
                        "runId", run.getId(),
                        "workItemId", run.getWorkItemId(),
                        "status", status.name()),
                0));
    }
}

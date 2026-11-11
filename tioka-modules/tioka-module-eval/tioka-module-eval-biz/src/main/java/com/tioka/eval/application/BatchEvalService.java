package com.tioka.eval.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.api.dataset.EvalDatasetView;
import com.tioka.eval.api.mq.EvalMqTopics;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.evaluator.EvalTexts;
import com.tioka.eval.evaluator.EvaluatorRegistry;
import com.tioka.eval.infrastructure.EvalRunRepository;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO;
import com.tioka.eval.infrastructure.persistence.EvalResultDO;
import com.tioka.eval.infrastructure.persistence.EvalRunDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.TiokaExecutors;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EVO-02：批次评测执行（M3-06 §4.2 / ADR-031）。
 *
 * <p><b>异步机制</b>：{@code startRun} 落 RUNNING 后提交到本服务私有的单线程 daemon 池
 * 逐 Run 顺序执行（设计未规定跨实例分发机制；单实例内异步为现状最简可靠方式，登记报告）。
 * Run 结束在同一事务发 {@code tioka.eval.run_finished.v1}（outbox）。
 *
 * <p><b>隔离 thread</b>：每条用例经 {@link RuntimeApi#createThread} 独立会话真跑，
 * thread 与 turn 的 origin 均为 {@code eval}，originRef=runId（ADR-031「隔离的 thread 里运行」）。
 *
 * <p><b>工具预演</b>：eval turn 一律 {@code approvalPolicy=always}（{@code toolAllowList} 不收窄，
 * 保持 Agent 配置可见）——所有工具调用（含只读）都进入审批且评测运行永不批准；观察到
 * WAITING_APPROVAL 即记该用例为拦截态并 {@code interrupt}（RuntimeApi 对 WAITING 态直接
 * CANCELLED + PENDING 审批 EXPIRED），外部状态零变更——即 ADR-031「工具走桩或只读」的现状落地。
 *
 * <p><b>评测预算</b>：llm_judge 经 ModelGateway purpose=EVALUATION 计入空间评测预算、
 * 不占用户配额（ModelPurpose / UsageRecorder 既有口径）。
 */
public class BatchEvalService implements EvalApi {

    private static final Logger log = LoggerFactory.getLogger(BatchEvalService.class);

    public static final String ORIGIN_EVAL = "eval";
    public static final String KIND_BATCH = "BATCH";
    public static final String SUBJECT_AGENT_VERSION = "AGENT_VERSION";
    public static final String SUBJECT_FLOW_PUBLISH = "FLOW_PUBLISH";
    private static final String APPROVAL_ALWAYS = "always";
    private static final int EVAL_MAX_STEPS = 3;
    private static final long POLL_INTERVAL_MS = 200;

    private final DatasetService datasets;
    private final EvalRunRepository runs;
    private final EvaluatorRegistry registry;
    private final RuntimeApi runtime;
    private final PrincipalHolder principals;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final long caseTimeoutMillis;
    private final ExecutorService executor;

    public BatchEvalService(DatasetService datasets,
                            EvalRunRepository runs,
                            EvaluatorRegistry registry,
                            RuntimeApi runtime,
                            PrincipalHolder principals,
                            EventPublisher publisher,
                            ObjectMapper mapper,
                            PlatformTransactionManager transactionManager,
                            long caseTimeoutSeconds) {
        this.datasets = datasets;
        this.runs = runs;
        this.registry = registry;
        this.runtime = runtime;
        this.principals = principals;
        this.publisher = publisher;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.caseTimeoutMillis = caseTimeoutSeconds * 1000L;
        this.executor = TiokaExecutors.wrap(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eval-batch");
            t.setDaemon(true);
            return t;
        }));
    }

    // ---- EvalApi：数据集部分委托 DatasetService ----

    @Override
    public EvalDatasetView createDataset(CreateDatasetCmd cmd) {
        return datasets.createDataset(cmd);
    }

    @Override
    public DatasetVersionView importCases(String datasetId, List<EvalCase> cases) {
        return datasets.importCases(datasetId, cases);
    }

    // ---- EvalApi：批次运行 ----

    @Override
    public String startRun(StartEvalRunCmd cmd) {
        if (cmd == null || cmd.datasetId() == null || cmd.datasetId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "datasetId 必填");
        }
        if (cmd.subjectRef() == null || cmd.subjectRef().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "subjectRef 必填");
        }
        if (cmd.evaluatorIds() == null || cmd.evaluatorIds().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "evaluatorIds 不能为空");
        }
        String subjectType = cmd.subjectType() == null || cmd.subjectType().isBlank()
                ? SUBJECT_AGENT_VERSION : cmd.subjectType().trim();
        if (SUBJECT_FLOW_PUBLISH.equals(subjectType)) {
            // Flow 域挂起（M3-06 §4.2：FLOW_PUBLISH 分支登记未支持）
            throw new BizException(ErrorCode.NOT_IMPLEMENTED,
                    "FLOW_PUBLISH 被测对象暂不支持（Flow 域挂起）");
        }
        if (!SUBJECT_AGENT_VERSION.equals(subjectType)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "subjectType 仅支持 AGENT_VERSION / FLOW_PUBLISH: " + subjectType);
        }
        if (datasets.findDataset(cmd.datasetId()) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        Principal principal = principals.current();
        if (principal.workspaceId() == null || principal.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行须归属空间");
        }
        Map<String, EvalEvaluatorDO> evaluatorRows = datasets.evaluatorsById(cmd.evaluatorIds());
        for (String id : cmd.evaluatorIds()) {
            EvalEvaluatorDO row = evaluatorRows.get(id);
            if (row == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "评估器不存在: " + id);
            }
            // 空间隔离：第三方评估器行（workspace_id 非 NULL）须归属当前空间；
            // NULL = 平台内置（ScopeLine 旁路读取，见 EvalEvaluatorMapper）
            if (row.getWorkspaceId() != null && !row.getWorkspaceId().isBlank()
                    && !row.getWorkspaceId().equals(principal.workspaceId())) {
                throw new BizException(ErrorCode.NOT_FOUND, "评估器不属于当前空间: " + id);
            }
            if (registry.byType(row.getType()).isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知评估器类型: " + row.getType());
            }
        }

        EvalRunDO run = new EvalRunDO();
        run.setId(Ids.newId("run"));
        run.setWorkspaceId(principal.workspaceId());
        run.setKind(KIND_BATCH);
        run.setDatasetId(cmd.datasetId());
        run.setDatasetVersion(cmd.datasetVersion());
        run.setSubjectType(subjectType);
        run.setSubjectRef(cmd.subjectRef());
        run.setStatus("RUNNING");
        run.setRunAsJson(runAsJson(principal));
        runs.insertRun(run);
        executor.execute(() -> executeRun(run.getId(), principal, List.copyOf(cmd.evaluatorIds())));
        return run.getId();
    }

    @Override
    public EvalRunView getRun(String runId) {
        EvalRunDO run = runs.findRun(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        List<ResultView> results = new java.util.ArrayList<>();
        for (EvalResultDO r : runs.listResults(runId)) {
            results.add(new ResultView(r.getCaseId(), r.getTurnId(), r.getEvaluatorId(),
                    r.getScore(), r.getPassed(), r.getReason()));
        }
        return new EvalRunView(run.getId(), run.getWorkspaceId(), run.getKind(),
                run.getSubjectType(), run.getSubjectRef(),
                run.getDatasetId(), run.getDatasetVersion(),
                run.getStatus(), run.getSummaryJson(), results);
    }

    // ---- 执行 ----

    private record SingleResult(EvalScore score) {}

    private record CaseOutcome(String turnId, boolean blocked, boolean allPassed,
                               Map<String, SingleResult> results) {}

    private void executeRun(String runId, Principal principal, List<String> evaluatorIds) {
        try {
            principals.runAs(principal, () -> executeRunAs(runId, evaluatorIds));
        } catch (Exception e) {
            log.error("eval run {} executor crashed", runId, e);
        }
    }

    private void executeRunAs(String runId, List<String> evaluatorIds) {
        EvalRunDO run = runs.findRun(runId);
        if (run == null) {
            return;
        }
        try {
            List<EvalCaseDO> cases = datasets.casesForRun(run.getDatasetId(), run.getDatasetVersion());
            if (cases.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "数据集版本无用例");
            }
            Map<String, EvalEvaluatorDO> evaluatorRows = new LinkedHashMap<>();
            for (String id : evaluatorIds) {
                EvalEvaluatorDO row = datasets.evaluatorsById(List.of(id)).get(id);
                if (row != null) {
                    evaluatorRows.put(id, row);
                }
            }

            int passed = 0;
            int failed = 0;
            int blocked = 0;
            Map<String, Map<String, Object>> byEvaluator = new LinkedHashMap<>();
            for (EvalEvaluatorDO e : evaluatorRows.values()) {
                byEvaluator.put(e.getId(), counter());
            }

            for (EvalCaseDO c : cases) {
                CaseOutcome outcome = runCase(run, c, evaluatorRows);
                if (outcome.blocked()) {
                    blocked++;
                } else if (outcome.allPassed()) {
                    passed++;
                } else {
                    failed++;
                }
                for (Map.Entry<String, SingleResult> e : outcome.results().entrySet()) {
                    EvalResultDO row = new EvalResultDO();
                    row.setId(Ids.newId("res"));
                    row.setRunId(runId);
                    row.setTurnId(outcome.turnId());
                    row.setCaseId(c.getId());
                    row.setEvaluatorId(e.getKey());
                    row.setScore(e.getValue().score().score());
                    row.setPassed(e.getValue().score().passed());
                    row.setReason(e.getValue().score().reason());
                    tx.executeWithoutResult(s -> runs.insertResult(row));
                    Map<String, Object> counter = byEvaluator.get(e.getKey());
                    counter.put("passed", (int) counter.get("passed")
                            + (e.getValue().score().passed() ? 1 : 0));
                    counter.put("failed", (int) counter.get("failed")
                            + (e.getValue().score().passed() ? 0 : 1));
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalCases", cases.size());
            summary.put("evaluators", evaluatorRows.size());
            summary.put("results", cases.size() * evaluatorRows.size());
            summary.put("passedCases", passed);
            summary.put("failedCases", failed);
            summary.put("blockedCases", blocked);
            summary.put("byEvaluator", byEvaluator);
            publishFinished(runId, "COMPLETED", mapper.writeValueAsString(summary));
        } catch (Exception e) {
            log.error("eval run {} failed", runId, e);
            try {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                publishFinished(runId, "FAILED", mapper.writeValueAsString(summary));
            } catch (Exception fatal) {
                log.error("eval run {} 终态回写失败", runId, fatal);
            }
        }
    }

    /** 单条用例：隔离 thread + origin=eval 真跑；WAITING_APPROVAL = 工具被预演拦截。 */
    private CaseOutcome runCase(EvalRunDO run, EvalCaseDO c,
                                Map<String, EvalEvaluatorDO> evaluatorRows) {
        JsonNode input = read(c.getInputJson());
        JsonNode expected = read(c.getExpectedJson());
        String inputText = EvalTexts.text(input);

        RuntimeApi.ThreadView thread = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                run.getSubjectRef(), ORIGIN_EVAL, run.getId(), null,
                Map.of("evalRunId", run.getId(), "evalCaseId", c.getId())));
        RuntimeApi.TurnHandle handle = runtime.startTurn(thread.threadId(),
                new RuntimeApi.StartTurnCmd(
                        "eval-" + run.getId() + "-" + c.getId(),
                        List.of(RuntimeApi.InputItem.text(inputText)),
                        new RuntimeApi.TurnOptions(EVAL_MAX_STEPS, APPROVAL_ALWAYS, null),
                        ORIGIN_EVAL, run.getId()));

        boolean blocked = false;
        TurnStatus status = awaitTerminal(handle.turnId());
        if (status == TurnStatus.WAITING_APPROVAL) {
            // 评测运行永不批准：工具调用（含写）在此被拦截，interrupt 落 CANCELLED
            blocked = true;
            runtime.interrupt(handle.turnId());
            status = awaitTerminal(handle.turnId());
        }

        String actual = lastAssistantText(thread.threadId());

        Map<String, SingleResult> results = new LinkedHashMap<>();
        boolean allPassed = true;
        for (EvalEvaluatorDO e : evaluatorRows.values()) {
            EvalSubject subject = new EvalSubject(c.getId(), input, expected,
                    mapper.valueToTree(actual), handle.turnId());
            EvalScore score = registry.byType(e.getType()).orElseThrow()
                    .score(subject, readConfig(e.getConfigJson()));
            results.put(e.getId(), new SingleResult(score));
            allPassed = allPassed && score.passed();
        }
        return new CaseOutcome(handle.turnId(), blocked, allPassed, results);
    }

    /** 轮询直至终态；WAITING_APPROVAL 返回给调用方决定拦截处理；超时抛异常（run 记 FAILED）。 */
    private TurnStatus awaitTerminal(String turnId) {
        long deadline = System.currentTimeMillis() + caseTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            RuntimeApi.TurnView turn = runtime.getTurn(turnId);
            TurnStatus status = turn.status();
            if (status.isTerminal() || status == TurnStatus.WAITING_APPROVAL) {
                return status;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.INTERNAL, "评测等待被中断");
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "评测 turn 超时未终态: " + turnId);
    }

    private String lastAssistantText(String threadId) {
        List<RuntimeApi.ItemView> items = runtime.listItems(threadId, 0, 500);
        for (int i = items.size() - 1; i >= 0; i--) {
            RuntimeApi.ItemView item = items.get(i);
            if ("assistant".equals(item.role()) && item.content() != null
                    && item.content().get("text") != null) {
                return String.valueOf(item.content().get("text"));
            }
        }
        return "";
    }

    private void publishFinished(String runId, String status, String summaryJson) {
        tx.executeWithoutResult(s -> {
            runs.finishRun(runId, status, summaryJson);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("status", status);
            payload.put("summaryJson", summaryJson);
            publisher.publish(new DomainEvent(
                    EvalMqTopics.EVAL_RUN_FINISHED,
                    EvalMqTopics.TOPIC,
                    runId,
                    payload,
                    0,
                    null,
                    "eval_run:" + runId));
        });
    }

    private Map<String, Object> readConfig(String configJson) {
        try {
            return mapper.readValue(configJson == null || configJson.isBlank() ? "{}" : configJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "null" : json);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    private Map<String, Object> counter() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", 0);
        m.put("failed", 0);
        return m;
    }

    /** run_as_json（与 KernelRuntimeService 同形：身份不落权限集）。 */
    private String runAsJson(Principal p) {
        try {
            Principal identity = p.identityOnly();
            return mapper.writeValueAsString(Map.of(
                    "userId", identity.userId(),
                    "workspaceId", identity.workspaceId() == null ? "" : identity.workspaceId(),
                    "type", identity.type().name()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "run_as 序列化失败");
        }
    }
}

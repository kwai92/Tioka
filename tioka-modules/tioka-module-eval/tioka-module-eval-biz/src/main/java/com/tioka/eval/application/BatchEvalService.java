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
import com.tioka.eval.stats.ClusteredStats;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.TiokaExecutors;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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
 * <p><b>多次运行（C-18 EVO-07 / ADR-068 第 5 节）</b>：{@code trials}（1~10，缺省 1），
 * 每 (case × evaluator × trial) 落一条 EvalResultDO（trial 列 = 序号）；每 trial 独立
 * 隔离 thread（thread 间无共享记忆，origin=eval 不变），审批拦截 / 预演语义逐 trial
 * 保持；并发度不变（单线程池顺序执行）。summary_json 顶层加 {@code trials} 与
 * {@code metrics}（按评估器分组：singleSuccessRate / passK / consistencyRate /
 * clusteredCi，STALE/DISPUTED 用例排除并计 excludedCount，ADR-068 第 6.1 节）。
 * must_pass 用例任一判定失败计 {@code mustPassFailed}（门禁阻断决策无批次调用方，
 * 本批只计数上报，登记偏离——ADR-068 L153-165 的阻断在 EVO-06 门禁接入）。
 *
 * <p><b>工具预演</b>：eval turn 一律 {@code approvalPolicy=always}（{@code toolAllowList} 不收窄，
 * 保持 Agent 配置可见）——所有工具调用（含只读）都进入审批且评测运行永不批准；观察到
 * WAITING_APPROVAL 即记该用例为拦截态并 {@code interrupt}（RuntimeApi 对 WAITING 态直接
 * CANCELLED + PENDING 审批 EXPIRED），外部状态零变更——即 ADR-031「工具走桩或只读」的现状落地。
 *
 * <p><b>评测预算</b>：llm_judge 经 ModelGateway purpose=EVALUATION 计入空间评测预算、
 * 不占用户配额（ModelPurpose / UsageRecorder 既有口径）。
 *
 * <p><b>密封集隔离（C-18 EVO-07 / ADR-068 L47）</b>：数据集 tier=SEALED 且当前主体无
 * {@code eval:sealed:read} 权限时，{@link #getRun} 的 results 置空并在 summaryJson 加
 * {@code sealedRedacted=true}（汇总指标照常可见）；逐条内容可见性同 DatasetService 口径。
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
    /** trials 边界（ADR-068 第 5 节：可配 1~10）。 */
    private static final int TRIALS_MIN = 1;
    private static final int TRIALS_MAX = 10;

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
        int trials = normalizeTrials(cmd.trials());
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
        run.setTrials(trials);
        run.setRunAsJson(runAsJson(principal));
        runs.insertRun(run);
        executor.execute(() -> executeRun(run.getId(), principal, List.copyOf(cmd.evaluatorIds())));
        return run.getId();
    }

    /** trials 归一化：null 缺省 1；显式越界（&lt;1 或 &gt;10）拒绝（ADR-068 第 5 节 1~10）。 */
    private int normalizeTrials(Integer trials) {
        if (trials == null) {
            return 1;
        }
        if (trials < TRIALS_MIN || trials > TRIALS_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "trials 仅支持 1~10: " + trials);
        }
        return trials;
    }

    @Override
    public EvalRunView getRun(String runId) {
        EvalRunDO run = runs.findRun(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        // 密封集隔离（ADR-068 L47）：SEALED 数据集的逐条结果仅 eval:sealed:read 可见，
        // 其他人只能看汇总指标——results 置空 + summaryJson 加 sealedRedacted 标记
        boolean sealedRedact = isSealedRedacted(run);
        List<ResultView> results = new ArrayList<>();
        if (!sealedRedact) {
            for (EvalResultDO r : runs.listResults(runId)) {
                results.add(new ResultView(r.getCaseId(), r.getTurnId(), r.getEvaluatorId(),
                        r.getTrial() == null ? 1 : r.getTrial(),
                        r.getScore(), r.getPassed(), r.getReason()));
            }
        }
        String summaryJson = run.getSummaryJson();
        if (sealedRedact) {
            summaryJson = withSealedRedacted(summaryJson);
        }
        return new EvalRunView(run.getId(), run.getWorkspaceId(), run.getKind(),
                run.getSubjectType(), run.getSubjectRef(),
                run.getDatasetId(), run.getDatasetVersion(),
                run.getStatus(), summaryJson, results);
    }

    private boolean isSealedRedacted(EvalRunDO run) {
        if (run.getDatasetId() == null) {
            return false;
        }
        com.tioka.eval.infrastructure.persistence.EvalDatasetDO dataset =
                datasets.findDataset(run.getDatasetId());
        if (dataset == null || !DatasetService.TIER_SEALED.equals(dataset.getTier())) {
            return false;
        }
        try {
            return !principals.current().hasPermission(Permissions.EVAL_SEALED_READ);
        } catch (BizException e) {
            return true; // 无身份上下文按无权限处理（保守脱敏）
        }
    }

    private String withSealedRedacted(String summaryJson) {
        try {
            Map<String, Object> summary = summaryJson == null || summaryJson.isBlank()
                    ? new LinkedHashMap<>()
                    : mapper.readValue(summaryJson, new TypeReference<Map<String, Object>>() {});
            summary.put("sealedRedacted", true);
            return mapper.writeValueAsString(summary);
        } catch (Exception e) {
            return "{\"sealedRedacted\":true}";
        }
    }

    // ---- 执行 ----

    /** 单 trial 的执行产物。 */
    private record TrialOutcome(String turnId, boolean blocked, boolean allPassed,
                                String actualText,
                                Map<String, EvalScore> results) {}

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
            int trials = run.getTrials() == null || run.getTrials() <= 0 ? 1 : run.getTrials();

            int passed = 0;
            int failed = 0;
            int blocked = 0;
            int mustPassFailed = 0;
            Map<String, Map<String, Object>> byEvaluator = new LinkedHashMap<>();
            for (EvalEvaluatorDO e : evaluatorRows.values()) {
                byEvaluator.put(e.getId(), counter());
            }
            // C-18 EVO-07：按评估器分组的多次运行指标（ADR-068 第 5/6.1 节）
            Map<String, List<Double>> perCaseRates = new LinkedHashMap<>();  // evaluator → 每 case 成功率
            Map<String, List<Boolean>> perCaseAllPass = new LinkedHashMap<>(); // evaluator → 每 case pass^k
            Map<String, List<Boolean>> perCaseConsistent = new LinkedHashMap<>(); // evaluator → 每 case 结论一致
            Map<String, Map<String, List<Double>>> unitJudgments = new LinkedHashMap<>(); // evaluator → unit → 判定
            int excludedCases = 0;

            for (EvalCaseDO c : cases) {
                boolean valid = c.getLabelStatus() == null
                        || DatasetService.LABEL_STATUS_DEFAULT.equals(c.getLabelStatus());
                if (!valid) {
                    // STALE/DISPUTED 不参与门禁统计，只在报告中列数量（ADR-068 第 2 节 L85）
                    excludedCases++;
                }
                String unitKey = c.getUnitKey() == null || c.getUnitKey().isBlank()
                        ? "case:" + c.getId() : c.getUnitKey();

                List<TrialOutcome> trialOutcomes = new ArrayList<>(trials);
                boolean anyBlocked = false;
                for (int t = 1; t <= trials; t++) {
                    TrialOutcome outcome = runCase(run, c, evaluatorRows, t);
                    trialOutcomes.add(outcome);
                    anyBlocked = anyBlocked || outcome.blocked();
                }

                // 结果行与 byEvaluator 计数对 blocked 用例照常落（既有口径：
                // 拦截后取 lastAssistantText 参与打分，EVO-02 BatchEvalIT 断言依赖）
                boolean allTrialsAllEvaluatorsPassed = true;
                boolean anyJudgmentFailed = false;
                for (int t = 0; t < trials; t++) {
                    TrialOutcome outcome = trialOutcomes.get(t);
                    for (Map.Entry<String, EvalScore> e : outcome.results().entrySet()) {
                        EvalScore score = e.getValue();
                        allTrialsAllEvaluatorsPassed = allTrialsAllEvaluatorsPassed && score.passed();
                        anyJudgmentFailed = anyJudgmentFailed || !score.passed();
                        EvalResultDO row = new EvalResultDO();
                        row.setId(Ids.newId("res"));
                        row.setRunId(runId);
                        row.setTurnId(outcome.turnId());
                        row.setCaseId(c.getId());
                        row.setEvaluatorId(e.getKey());
                        row.setTrial(t + 1);
                        row.setScore(score.score());
                        row.setPassed(score.passed());
                        row.setReason(score.reason());
                        tx.executeWithoutResult(s -> runs.insertResult(row));
                        Map<String, Object> counter = byEvaluator.get(e.getKey());
                        counter.put("passed", (int) counter.get("passed")
                                + (score.passed() ? 1 : 0));
                        counter.put("failed", (int) counter.get("failed")
                                + (score.passed() ? 0 : 1));
                    }
                }

                // 用例级通过口径（trials=1 与既有语义一致；trials>1 取全 trial 全评估器通过）
                if (anyBlocked) {
                    blocked++;
                } else if (allTrialsAllEvaluatorsPassed) {
                    passed++;
                } else {
                    failed++;
                }
                if (!anyBlocked && anyJudgmentFailed && Boolean.TRUE.equals(c.getMustPass())) {
                    // must_pass 任一失败（ADR-068 L162 回归集逐条必过；本批只计数，登记偏离）
                    mustPassFailed++;
                }

                // 指标累计（仅 VALID 用例参与；ADR-068 第 2 节 label_status ≠ VALID 排除；
                // 被审批拦截的用例无有效判定，同样不计入 metrics，登记偏离）
                if (valid && !anyBlocked) {
                    // 关键输出一致性：各 trial 的 actual 文本完全一致（EvalTexts.text 口径：
                    // actual 为纯文本字符串，text(文本节点) = 原串，直接比较即同口径）
                    String firstActual = trialOutcomes.get(0).actualText();
                    boolean consistent = true;
                    for (TrialOutcome t : trialOutcomes) {
                        if (!firstActual.equals(t.actualText())) {
                            consistent = false;
                            break;
                        }
                    }
                    for (EvalEvaluatorDO e : evaluatorRows.values()) {
                        double caseRate = 0;
                        boolean allPass = true;
                        for (TrialOutcome t : trialOutcomes) {
                            EvalScore score = t.results().get(e.getId());
                            caseRate += score != null && score.passed() ? 1 : 0;
                            allPass = allPass && score != null && score.passed();
                        }
                        caseRate /= trials;
                        perCaseRates.computeIfAbsent(e.getId(), k -> new ArrayList<>()).add(caseRate);
                        perCaseAllPass.computeIfAbsent(e.getId(), k -> new ArrayList<>()).add(allPass);
                        perCaseConsistent.computeIfAbsent(e.getId(), k -> new ArrayList<>()).add(consistent);
                        Map<String, List<Double>> units =
                                unitJudgments.computeIfAbsent(e.getId(), k -> new LinkedHashMap<>());
                        List<Double> judgments =
                                units.computeIfAbsent(unitKey, k -> new ArrayList<>());
                        for (TrialOutcome t : trialOutcomes) {
                            EvalScore score = t.results().get(e.getId());
                            judgments.add(score != null && score.passed() ? 1.0 : 0.0);
                        }
                    }
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalCases", cases.size());
            summary.put("evaluators", evaluatorRows.size());
            summary.put("results", cases.size() * evaluatorRows.size() * trials);
            summary.put("passedCases", passed);
            summary.put("failedCases", failed);
            summary.put("blockedCases", blocked);
            summary.put("byEvaluator", byEvaluator);
            summary.put("trials", trials);
            summary.put("mustPassFailed", mustPassFailed);
            summary.put("metrics", buildMetrics(trials, evaluatorRows, perCaseRates,
                    perCaseAllPass, perCaseConsistent, unitJudgments, excludedCases));
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

    /**
     * 按评估器分组的多次运行指标（ADR-068 第 5 节 + 第 6.1 节；仅 VALID 用例参与）。
     * 单次成功率 = 各次运行成功率的均值（题内方差缩减，L133）；
     * passK = k 次全部成功的用例比例（L134）；结论一致率 = k 次关键输出完全一致的
     * 用例比例（L135）；clusteredCi = 以材料单元为聚类单位的区间估计
     * （ClusteredStats：聚类 SE + Wilson，excludedCount 为 label_status ≠ VALID 排除数，
     * unitCount &lt; 10 标样本不足，L151）。
     */
    private Map<String, Object> buildMetrics(int trials,
                                             Map<String, EvalEvaluatorDO> evaluatorRows,
                                             Map<String, List<Double>> perCaseRates,
                                             Map<String, List<Boolean>> perCaseAllPass,
                                             Map<String, List<Boolean>> perCaseConsistent,
                                             Map<String, Map<String, List<Double>>> unitJudgments,
                                             int excludedCases) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (EvalEvaluatorDO e : evaluatorRows.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Double> rates = perCaseRates.getOrDefault(e.getId(), List.of());
            double singleSuccessRate = rates.isEmpty() ? 0.0 : avg(rates);
            double passK = ratio(perCaseAllPass.get(e.getId()));
            double consistencyRate = ratio(perCaseConsistent.get(e.getId()));
            m.put("singleSuccessRate", singleSuccessRate);
            m.put("passK", passK);
            m.put("consistencyRate", consistencyRate);
            ClusteredStats.ClusteredCi ci = ClusteredStats.clustered(
                    unitJudgments.getOrDefault(e.getId(), Map.of()));
            Map<String, Object> ciMap = new LinkedHashMap<>();
            ciMap.put("mean", ci.clusteredMean());
            ciMap.put("clusterSe", ci.clusterSe());
            ciMap.put("ci95Lower", ci.ci95Lower());
            ciMap.put("ci95Upper", ci.ci95Upper());
            ciMap.put("unitCount", ci.unitCount());
            ciMap.put("judgmentCount", ci.judgmentCount());
            ciMap.put("effectiveN", ci.effectiveN());
            ciMap.put("wilsonLower", ci.wilsonLower());
            ciMap.put("wilsonUpper", ci.wilsonUpper());
            ciMap.put("sampleSufficient", ci.sampleSufficient());
            ciMap.put("excludedCount", excludedCases);
            m.put("clusteredCi", ciMap);
            metrics.put(e.getId(), m);
        }
        return metrics;
    }

    private double avg(List<Double> values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return values.isEmpty() ? 0 : s / values.size();
    }

    private double ratio(List<Boolean> flags) {
        if (flags == null || flags.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (boolean f : flags) {
            hit += f ? 1 : 0;
        }
        return (double) hit / flags.size();
    }

    /**
     * 单条用例单 trial：隔离 thread + origin=eval 真跑；WAITING_APPROVAL = 工具被预演拦截。
     * trials &gt; 1 时逐 trial 调用（每次独立 thread，thread 间无共享记忆，ADR-068 第 5 节）。
     */
    private TrialOutcome runCase(EvalRunDO run, EvalCaseDO c,
                                 Map<String, EvalEvaluatorDO> evaluatorRows, int trial) {
        JsonNode input = read(c.getInputJson());
        JsonNode expected = read(c.getExpectedJson());
        String inputText = EvalTexts.text(input);

        RuntimeApi.ThreadView thread = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                run.getSubjectRef(), ORIGIN_EVAL, run.getId(), null,
                Map.of("evalRunId", run.getId(), "evalCaseId", c.getId(),
                        "evalTrial", String.valueOf(trial))));
        RuntimeApi.TurnHandle handle = runtime.startTurn(thread.threadId(),
                new RuntimeApi.StartTurnCmd(
                        "eval-" + run.getId() + "-" + c.getId() + "-t" + trial,
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

        Map<String, EvalScore> results = new LinkedHashMap<>();
        boolean allPassed = true;
        for (EvalEvaluatorDO e : evaluatorRows.values()) {
            EvalSubject subject = new EvalSubject(c.getId(), input, expected,
                    mapper.valueToTree(actual), handle.turnId());
            EvalScore score = registry.byType(e.getType()).orElseThrow()
                    .score(subject, readConfig(e.getConfigJson()));
            results.put(e.getId(), score);
            allPassed = allPassed && score.passed();
        }
        return new TrialOutcome(handle.turnId(), blocked, allPassed, actual, results);
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

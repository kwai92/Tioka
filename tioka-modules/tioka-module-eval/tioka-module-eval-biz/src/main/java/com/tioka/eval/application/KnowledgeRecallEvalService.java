package com.tioka.eval.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C-16（KNW-07 评测集与召回率基线进 CI）：知识检索召回评测执行器。
 * 设计出处 docs/architecture/design-notes/03-推进规划与详细设计-A至E.md L518
 * 「评测进 CI：评测集由问题 + 期望命中的文档或分块组成，指标取 Recall@5、MRR」。
 *
 * <p><b>与 BatchEvalService 的口径关系</b>：落表复用同一批 DO / mapper / repo
 * （EvalRunDO / EvalResultDO / EvalRunRepository），kind=BATCH、审计与 run_as_json
 * 照抄其写法；被测对象 subject_type=KNOWLEDGE_SEARCH（16 字符，恰合 eval_run.subject_type
 * VARCHAR(16)，V176 注释仅列 AGENT_VERSION/FLOW_PUBLISH——演进登记偏离候选）。
 * 本类不改 EvalApi 接口、不改 BatchEvalService。
 *
 * <p><b>同步执行（登记偏离候选）</b>：召回评测 = 纯检索 + 纯 JSON 打分，无 runtime turn
 * 轮询，单数据集规模小，{@code startRecallRun} 同步驱动至终态，CI 断言无需轮询；
 * BatchEvalService 的异步单线程池 + turn 隔离机制不适用（不依赖 RuntimeApi）。
 *
 * <p><b>不发 outbox（登记偏离候选）</b>：BatchEvalService 终态发
 * {@code tioka.eval.run_finished.v1}；召回评测为 CI 内部路径、无外部消费方，
 * 从简只落表（若后续需要事件再补，见 BatchEvalService.publishFinished）。
 *
 * <p><b>评估器固定口径</b>：本服务固定用 V223 种子的 recall_at_5 + mrr 两个内置评估器
 * （ID 常量），检索窗口 topK 与评估器 config.k 强制对齐（评分窗口 = 检索窗口）。
 */
public class KnowledgeRecallEvalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRecallEvalService.class);

    public static final String KIND_BATCH = "BATCH";
    /** 被测对象类型：知识检索（16 字符恰合 eval_run.subject_type VARCHAR(16)）。 */
    public static final String SUBJECT_KNOWLEDGE_SEARCH = "KNOWLEDGE_SEARCH";
    /** V223 内置评估器种子行 ID（固定口径，见类注释）。 */
    public static final String EVALUATOR_RECALL_AT_5 = "evl_builtin_recall_at_5";
    public static final String EVALUATOR_MRR = "evl_builtin_mrr";

    private final DatasetService datasets;
    private final EvalRunRepository runs;
    private final EvaluatorRegistry registry;
    private final ObjectProvider<KnowledgeSearchApi> searchApi;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public KnowledgeRecallEvalService(DatasetService datasets,
                                      EvalRunRepository runs,
                                      EvaluatorRegistry registry,
                                      ObjectProvider<KnowledgeSearchApi> searchApi,
                                      PrincipalHolder principals,
                                      ObjectMapper mapper,
                                      PlatformTransactionManager transactionManager) {
        this.datasets = datasets;
        this.runs = runs;
        this.registry = registry;
        this.searchApi = searchApi;
        this.principals = principals;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 发起一轮召回评测并同步执行至终态。
     *
     * @param datasetId 评测数据集（input_json={"query":...}，expected_json={"references":[docId...]})
     * @param kbId      被测知识库（subject_ref）
     * @param topK      检索窗口（同时作为 recall/mrr 评估器的 k）
     * @return runId
     */
    public String startRecallRun(String datasetId, String kbId, int topK) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "datasetId 必填");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kbId 必填");
        }
        if (searchApi.getIfAvailable() == null) {
            throw new BizException(ErrorCode.NOT_IMPLEMENTED,
                    "知识检索模块未装配（KnowledgeSearchApi 缺席，检查 tioka-module-knowledge）");
        }
        Principal principal = principals.current();
        if (principal.workspaceId() == null || principal.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行须归属空间");
        }
        EvalDatasetDOJson dataset = datasetOrNull(datasetId);
        // 空间隔离：数据集行非本空间一律按不存在处理（同 BatchEvalService 评估器行口径）
        if (dataset.workspaceId != null && !dataset.workspaceId.isBlank()
                && !dataset.workspaceId.equals(principal.workspaceId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        List<EvalEvaluatorDO> evaluatorRows = evaluatorRows();

        EvalRunDO run = new EvalRunDO();
        run.setId(Ids.newId("run"));
        run.setWorkspaceId(principal.workspaceId());
        run.setKind(KIND_BATCH);
        run.setDatasetId(datasetId);
        run.setDatasetVersion(dataset.latestVersion);
        run.setSubjectType(SUBJECT_KNOWLEDGE_SEARCH);
        run.setSubjectRef(kbId);
        run.setStatus("RUNNING");
        run.setRunAsJson(runAsJson(principal));
        runs.insertRun(run);

        executeRun(run.getId(), principal, datasetId, kbId, Math.max(1, topK), dataset.latestVersion, evaluatorRows);
        return run.getId();
    }

    /** 运行状态 + 汇总 + 逐条结果（同 EvalApi.EvalRunView 形状）。 */
    public EvalRunView getRun(String runId) {
        EvalRunDO run = runs.findRun(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        List<ResultView> results = new ArrayList<>();
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

    private record CaseOutcome(Map<String, EvalScore> byEvaluatorId, double recall, double mrr, int rankFirst) {}

    private void executeRun(String runId, Principal principal, String datasetId, String kbId, int topK,
                            Integer datasetVersion, List<EvalEvaluatorDO> evaluatorRows) {
        try {
            // 检索按发起身份执行（KnowledgeService 内部取 principals.current() 的工作空间与权限）
            CaseSummary summary = principals.runAs(principal,
                    () -> executeRunAs(runId, datasetId, kbId, topK, datasetVersion, evaluatorRows));
            runs.finishRun(runId, "COMPLETED", mapper.writeValueAsString(summary.summary));
        } catch (Exception e) {
            log.error("knowledge recall eval run {} failed", runId, e);
            try {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                runs.finishRun(runId, "FAILED", mapper.writeValueAsString(summary));
            } catch (Exception fatal) {
                log.error("knowledge recall eval run {} 终态回写失败", runId, fatal);
            }
        }
    }

    private CaseSummary executeRunAs(String runId, String datasetId, String kbId, int topK,
                                     Integer datasetVersion, List<EvalEvaluatorDO> evaluatorRows) {
        List<EvalCaseDO> cases = datasets.casesForRun(datasetId, datasetVersion);
        if (cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集版本无用例");
        }
        KnowledgeSearchApi search = searchApi.getObject();

        double recallSum = 0;
        double mrrSum = 0;
        List<Map<String, Object>> perCase = new ArrayList<>(cases.size());
        int caseCount = 0;
        for (EvalCaseDO c : cases) {
            JsonNode input = read(c.getInputJson());
            JsonNode expected = read(c.getExpectedJson());
            String query = input.path("query").asText("");
            if (query.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用例 input 缺 query: " + c.getId());
            }
            List<KnowledgeSearchApi.Hit> hits = search.searchKnowledge(kbId, query, topK);
            JsonNode actual = actualOf(hits);
            int rankFirst = firstReferenceRank(hits, EvalTexts.references(expected));

            Map<String, EvalScore> byEvaluatorId = new LinkedHashMap<>();
            for (EvalEvaluatorDO e : evaluatorRows) {
                EvalSubject subject = new EvalSubject(c.getId(), input, expected, actual, null);
                Map<String, Object> config = readConfig(e.getConfigJson());
                config.put("k", topK); // 评估窗口与检索窗口对齐（固定口径，见类注释）
                EvalScore score = registry.byType(e.getType()).orElseThrow(() ->
                        new BizException(ErrorCode.BAD_REQUEST, "未知评估器类型: " + e.getType())).score(subject, config);
                byEvaluatorId.put(e.getId(), score);
                EvalResultDO row = new EvalResultDO();
                row.setId(Ids.newId("res"));
                row.setRunId(runId);
                row.setCaseId(c.getId());
                row.setEvaluatorId(e.getId());
                row.setScore(score.score());
                row.setPassed(score.passed());
                row.setReason(score.reason());
                EvalResultDO frozen = row;
                tx.executeWithoutResult(s -> runs.insertResult(frozen));
            }
            double recall = byEvaluatorId.get(EVALUATOR_RECALL_AT_5).score();
            double mrr = byEvaluatorId.get(EVALUATOR_MRR).score();
            recallSum += recall;
            mrrSum += mrr;
            caseCount++;
            Map<String, Object> pc = new LinkedHashMap<>();
            pc.put("caseId", c.getId());
            pc.put("recall", recall);
            pc.put("mrr", mrr);
            pc.put("rankFirst", rankFirst);
            perCase.add(pc);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasetId", cases.isEmpty() ? null : cases.get(0).getDatasetId());
        summary.put("kbId", kbId);
        summary.put("topK", topK);
        summary.put("totalCases", caseCount);
        summary.put("recallAt5", caseCount == 0 ? 0.0 : recallSum / caseCount);
        summary.put("mrr", caseCount == 0 ? 0.0 : mrrSum / caseCount);
        summary.put("perCase", perCase);
        return new CaseSummary(summary);
    }

    /** actual = {"hits":[{"docId","chunkId","score"},...]}（评估器 SPI 消费口径）。 */
    private JsonNode actualOf(List<KnowledgeSearchApi.Hit> hits) {
        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (KnowledgeSearchApi.Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", h.docId());
            m.put("chunkId", h.chunkId());
            m.put("score", h.score());
            items.add(m);
        }
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("hits", items);
        return mapper.valueToTree(actual);
    }

    /** 首个命中 reference 的 hits 排名（1-based；无命中 0），供 summary.perCase.rankFirst。 */
    private static int firstReferenceRank(List<KnowledgeSearchApi.Hit> hits, List<String> references) {
        for (int i = 0; i < hits.size(); i++) {
            if (references.contains(hits.get(i).docId())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 固定评估器组：V223 种子行（recall_at_5 + mrr），行缺失或 SPI 未注册即报错。 */
    private List<EvalEvaluatorDO> evaluatorRows() {
        Map<String, EvalEvaluatorDO> rows = datasets.evaluatorsById(
                List.of(EVALUATOR_RECALL_AT_5, EVALUATOR_MRR));
        for (String id : List.of(EVALUATOR_RECALL_AT_5, EVALUATOR_MRR)) {
            EvalEvaluatorDO row = rows.get(id);
            if (row == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "内置召回评估器种子缺失: " + id);
            }
            if (registry.byType(row.getType()).isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "评估器 SPI 未注册: " + row.getType());
            }
        }
        return List.of(rows.get(EVALUATOR_RECALL_AT_5), rows.get(EVALUATOR_MRR));
    }

    /** 数据集存在性（供空间隔离校验）；避免直接依赖 EvalDatasetDO 类型面。 */
    private EvalDatasetDOJson datasetOrNull(String datasetId) {
        var dataset = datasets.findDataset(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        return new EvalDatasetDOJson(dataset.getWorkspaceId(), dataset.getLatestVersion());
    }

    private record EvalDatasetDOJson(String workspaceId, Integer latestVersion) {}

    private record CaseSummary(Map<String, Object> summary) {}

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

    private Map<String, Object> readConfig(String configJson) {
        try {
            return mapper.readValue(configJson == null || configJson.isBlank() ? "{}" : configJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "null" : json);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    // ---- 视图（形状与 EvalApi.EvalRunView 一致，供 IT / 后续控制器复用） ----

    public record ResultView(String caseId, String turnId, String evaluatorId,
                             Double score, Boolean passed, String reason) {}

    public record EvalRunView(String runId, String workspaceId, String kind,
                              String subjectType, String subjectRef,
                              String datasetId, Integer datasetVersion,
                              String status, String summaryJson, List<ResultView> results) {}
}

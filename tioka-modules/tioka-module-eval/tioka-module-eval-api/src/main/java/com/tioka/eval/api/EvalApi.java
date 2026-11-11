package com.tioka.eval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.eval.api.dataset.EvalDatasetView;

import java.util.List;

/**
 * 评测域入口（EVO-02 / ADR-031 接口节）。实现在 eval-biz：
 * 数据集部分由 DatasetService 承担，批次运行由 BatchEvalService 承担
 * （后者仅在 {@code tioka.eval.batch.enabled=true} 时装配，接口消费方用 ObjectProvider 弱依赖）。
 *
 * <p>用户反馈见 {@link EvalFeedbackApi}（独立 API，EVO-01）。
 */
public interface EvalApi {

    /** 创建数据集（版本从 0 开始，用例经 {@link #importCases} 逐版本导入）。 */
    EvalDatasetView createDataset(CreateDatasetCmd cmd);

    /** 导入一批用例，落为新版本（latest_version + 1），返回版本摘要。 */
    DatasetVersionView importCases(String datasetId, List<EvalCase> cases);

    /**
     * 启动批次评测（异步）：选数据集版本 + 评估器组 + 被测对象。
     * 每条用例在隔离的 runtime thread 运行（origin=eval）；工具调用一律需审批且评测运行不批准
     * （ADR-031「走桩或只读」现状落地）；用量经 ModelGateway purpose=EVALUATION 计入评测预算。
     *
     * @return runId
     */
    String startRun(StartEvalRunCmd cmd);

    /** 运行状态 + 汇总 + 逐条结果。 */
    EvalRunView getRun(String runId);

    // ---- 命令 ----

    record CreateDatasetCmd(String name, String schemaJson) {}

    /** @param source MANUAL / IMPORT（缺省 IMPORT）；ONLINE 由轨迹回流写入，接口层不收 */
    record EvalCase(JsonNode input, JsonNode expected, String source) {}

    /**
     * @param datasetVersion 数据集版本；null = latest
     * @param subjectType    AGENT_VERSION / FLOW_PUBLISH（FLOW 域挂起，暂不支持）
     * @param subjectRef     被测对象引用（AGENT_VERSION 时为 agentVersionId）
     * @param evaluatorIds   eval_evaluator 行 ID 组
     */
    record StartEvalRunCmd(String datasetId, Integer datasetVersion, String subjectType,
                           String subjectRef, List<String> evaluatorIds) {}

    // ---- 视图 ----

    record DatasetVersionView(String datasetId, int version, int caseCount) {}

    record ResultView(String caseId, String turnId, String evaluatorId,
                      Double score, Boolean passed, String reason) {}

    record EvalRunView(String runId, String workspaceId, String kind,
                       String subjectType, String subjectRef,
                       String datasetId, Integer datasetVersion,
                       String status, String summaryJson, List<ResultView> results) {}
}

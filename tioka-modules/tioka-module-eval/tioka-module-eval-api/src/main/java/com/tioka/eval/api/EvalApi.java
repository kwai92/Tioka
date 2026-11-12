package com.tioka.eval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.eval.api.dataset.EvalDatasetView;

import java.util.List;

/**
 * 评测域入口（EVO-02 / ADR-031 接口节；C-18 EVO-07 按 ADR-068 扩展）。
 * 实现在 eval-biz：数据集部分由 DatasetService 承担，批次运行由 BatchEvalService 承担
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
     * trials &gt; 1 时每条用例跑多次，每 trial 独立隔离 thread（ADR-068 第 5 节）。
     *
     * @return runId
     */
    String startRun(StartEvalRunCmd cmd);

    /**
     * 运行状态 + 汇总 + 逐条结果。
     * 密封集隔离（ADR-068 L47）：数据集 tier=SEALED 且调用者无 {@code eval:sealed:read}
     * 权限时，results 置空、summaryJson 附 {@code sealedRedacted=true}（汇总指标照常可见）。
     */
    EvalRunView getRun(String runId);

    // ---- 命令 ----

    /**
     * @param tier        分层：SMOKE / DEV / SEALED / REGRESSION / SPECIAL（ADR-068 第 1 节）；
     *                    null 缺省 DEV
     * @param unitKeyPath 材料单元键路径（如 contract_key）；null = 未声明
     */
    record CreateDatasetCmd(String name, String schemaJson, String tier, String unitKeyPath) {

        /** 兼容旧 2 参口径（tier=DEV、unitKeyPath 未声明）。 */
        public CreateDatasetCmd(String name, String schemaJson) {
            this(name, schemaJson, null, null);
        }
    }

    /**
     * @param source      MANUAL / IMPORT（缺省 IMPORT）；ADR-068 第 2 节注释口径扩展
     *                    SHADOW / FEEDBACK / GENERATED（生成后须人工审核）——仅注释口径，
     *                    接口层暂不收（ONLINE 由轨迹回流写入，SHADOW/FEEDBACK/GENERATED
     *                    由后续批次接入），运行时校验仍为 MANUAL / IMPORT
     * @param unitKey     材料单元键；null = 未声明（unit_key_path 未声明时可空）
     * @param labelStatus VALID / STALE / DISPUTED（ADR-068 第 2 节）；null 缺省 VALID
     * @param mustPass    逐条必须通过（回归集缺省为是）；null 缺省 false
     */
    record EvalCase(JsonNode input, JsonNode expected, String source,
                    String unitKey, String labelStatus, Boolean mustPass) {

        /** 兼容旧 3 参口径（unitKey 未声明、labelStatus=VALID、mustPass=false）。 */
        public EvalCase(JsonNode input, JsonNode expected, String source) {
            this(input, expected, source, null, null, null);
        }
    }

    /**
     * @param datasetVersion 数据集版本；null = latest
     * @param subjectType    AGENT_VERSION / FLOW_PUBLISH（FLOW 域挂起，暂不支持）
     * @param subjectRef     被测对象引用（AGENT_VERSION 时为 agentVersionId）
     * @param evaluatorIds   eval_evaluator 行 ID 组
     * @param trials         每条用例运行次数（ADR-068 第 5 节）；null 缺省 1，越界 1~10 拒绝
     */
    record StartEvalRunCmd(String datasetId, Integer datasetVersion, String subjectType,
                           String subjectRef, List<String> evaluatorIds, Integer trials) {

        /** 兼容旧 5 参口径（trials=1）。 */
        public StartEvalRunCmd(String datasetId, Integer datasetVersion, String subjectType,
                               String subjectRef, List<String> evaluatorIds) {
            this(datasetId, datasetVersion, subjectType, subjectRef, evaluatorIds, null);
        }
    }

    // ---- 视图 ----

    record DatasetVersionView(String datasetId, int version, int caseCount) {}

    /** @param trial 运行序号 1..trials（C-18 EVO-07，V225 落列） */
    record ResultView(String caseId, String turnId, String evaluatorId, int trial,
                      Double score, Boolean passed, String reason) {

        /** 兼容旧 6 参口径（trial=1）。 */
        public ResultView(String caseId, String turnId, String evaluatorId,
                          Double score, Boolean passed, String reason) {
            this(caseId, turnId, evaluatorId, 1, score, passed, reason);
        }
    }

    record EvalRunView(String runId, String workspaceId, String kind,
                       String subjectType, String subjectRef,
                       String datasetId, Integer datasetVersion,
                       String status, String summaryJson, List<ResultView> results) {}
}

# ADR-031 — Agent 观测、评测与经验自进化

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：EVO-01〜06（新增）
- 对应设计：设计 O
- 对标：阿里云 [AgentLoop](https://help.aliyun.com/zh/agentloop/product-overview/core-concept)（Agent 观测与优化）
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

设计 A–N 已经有轨迹（ADR-016）、知识库评测（ADR-018）、用户记忆（ADR-022）和链路追踪（ADR-025），但缺一条闭环：**线上轨迹 → 评测 → 实验对比 → 优化资产 → 再评测**。AgentLoop 把它称为数据飞轮。本设计补齐这条闭环。核心决定是：**评测与经验都建立在 Runtime 自带的轨迹上，不另接 SDK；所有评测用的模型调用都经 ModelGateway 计入配额；经验和 Prompt / Skill 一样是带版本的资产，默认需要人审后才生效。**

## 概念对照

| AgentLoop | Tioka | 说明 |
| --- | --- | --- |
| AgentSpace | 空间（workspace） | 数据集、评测、实验、经验都归属空间，复用 ADR-015 权限 |
| Agent 应用 | Agent 已发布版本 | 评测与实验以版本为单位，结果可回溯到 AgentSpec 快照（ADR-023） |
| 调用轨迹 Trajectory | thread / turn / item | ADR-016 已落库。另提供导出接口，格式参照 ATIF 字段（会话 → Turn → 步骤 → 模型调用 / 工具调用），供外部分析 |
| 数据集 Dataset | `eval_dataset` | 版本化；字段 Schema 自定义（text / number / json）；来源可以是手工、导入、线上轨迹回流 |
| 数据处理 Pipeline | 采样与加工任务 | 过滤、去重、采样 → 特征提取（步数、工具集、耗时、token）→ 可选 AI 标注（意图、主题） |
| 评估 Evaluation | 评估器 + 评测运行 | 规则评估器、LLM-as-a-Judge、Agent-as-a-Judge、人工反馈；线上持续评测与批次评测 |
| 实验 Experiment | `eval_experiment` | 固定数据集与评估器，变量取 Agent 版本、模型绑定、路由方案、Prompt / Skill 版本、参数 |
| Playground | Studio 试运行（HCI-06） | 试运行时可挂载评估器，即时出分 |
| Agent 资产 | Agent、Skill、Prompt 版本（ADR-023） | 发布新版本时自动跑回归评测（EVO-06） |
| 记忆 Memory | `chat_memory_item`（ADR-022） | 「记住用户与环境」 |
| 经验 Experience | `exp_item`（本设计） | 「Agent 自己怎样把事做好」，可在空间内多个 Agent 间共享 |

## 线上评测（EVO-01、EVO-03）

1. Turn 结束时 Runtime 经 outbox 发 `tioka.runtime.turn.completed.v1`（含 turnId、agentVersionId、步数、token、耗时、结局）。
2. 采样器按 Agent 配置的采样率（默认 5%，人工点踩、失败、超过 maxSteps 的 Turn 全量）挑选 Turn。
3. 线上评估器异步打分，无标准答案：任务完成度、工具使用合理性、事实一致性（有引用时对照引用原文）、安全。评估模型调用走 `ModelGateway`（purpose=EVALUATION），用量计入空间的评测预算，不占用户配额。
4. 分数写 `eval_result`，在轨迹视图和 Agent 看板上展示趋势。低于阈值的 Turn 进入「待标注」队列，人工确认后可回流到回归数据集。
5. 用户反馈（点赞、点踩、文字意见）写 `eval_feedback`，作为人工评估信号。

## 批次评测与实验（EVO-02、EVO-04）

- 批次评测：选数据集版本 + 评估器组 + 被测对象（Agent 版本或 Flow 发布），每条用例在隔离的 thread 里运行（origin=eval），工具调用默认走桩或只读模式，结果写 `eval_run` / `eval_result`。知识库评测（KNW-07）是它的一个特例，复用同一套表。
- 实验：在同一数据集上对比多个变量组合，报告准确率、质量分、平均步数、延迟 P50/P95、token 与成本；两组均值差异给出置信区间，样本不足时标注「不显著」。
- CI：Agent 与 Skill 的回归数据集可以在 CI 里用录制回放的模型桩跑（与 ADR-030 一致），每日一次用真实模型跑。

## 经验库（EVO-05）

1. **提取**：每日任务按 Agent 汇总近期轨迹，分成高分成功与失败两组；`ModelGateway`（purpose=EXTRACTION）从中提取经验条目：适用条件、做法或教训、正例 / 反例、证据 Turn。
2. **审核**：条目默认进入 DRAFT，由 Agent 负责人在 Studio 审核后生效；空间可对低风险 Agent 开启自动生效。
3. **注入**：上下文组装时按本轮输入检索同一 Agent（及空间共享）的生效经验，最多 3 条、合计不超过 800 token。ADR-027 的组装顺序修订为：平台安全指令 → Agent 指令 → always Skill → auto Skill 索引 → 用户记忆 → **经验** → 知识自动检索结果 → 压缩摘要 → 历史 item → 本轮输入。
4. **效果与淘汰**：记录每条经验的命中次数和命中 Turn 的线上评测分。命中后分数持续低于未命中组时自动降为 DRAFT 并通知负责人。

## 发布回归门禁（EVO-06）

Agent、Skill、Prompt 发布新版本时，若绑定了回归数据集，发布流程自动跑一次批次评测；总分低于当前稳定版本超过阈值（默认 2 个百分点）时阻止发布，或按空间的发布策略（ADR-025）转人工审批。

## 外部 Agent 接入（P2）

AgentLoop 支持无 SDK 采集外部框架。Tioka 内的 Agent 都在 Runtime 里运行，轨迹天然完整；外部 Agent 可按 OpenTelemetry GenAI 语义约定把 trace 推到 `POST /api/eval/traces`，转换成只读轨迹后参与评测，不参与经验注入。

## 接口

```java
public interface EvalApi {
    DatasetView createDataset(CreateDatasetCmd cmd);
    DatasetVersionView importCases(String datasetId, List<EvalCase> cases);   // 新版本
    String startRun(StartEvalRunCmd cmd);              // 批次评测，异步
    EvalRunView getRun(String runId);
    String startExperiment(StartExperimentCmd cmd);
    void feedback(String turnId, FeedbackCmd cmd);     // 用户反馈
}
public interface Evaluator {                            // SPI：规则 / LLM-as-a-Judge / Agent-as-a-Judge
    String type();
    EvalScore score(EvalSubject subject, Map<String, Object> config);
}
public interface ExperienceApi {
    List<ExperienceView> retrieve(String agentId, String query, int limit);   // Runtime 组装上下文时调用
    void review(String experienceId, boolean approve, String comment);
}
```

## 数据模型

```sql
CREATE TABLE eval_dataset (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, name VARCHAR(255) NOT NULL,
  schema_json TEXT NOT NULL, latest_version INT NOT NULL DEFAULT 0, create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_at TIMESTAMPTZ NOT NULL DEFAULT now(), create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE eval_case (id VARCHAR(32) PRIMARY KEY, dataset_id VARCHAR(32) NOT NULL, version INT NOT NULL,
  input_json TEXT NOT NULL, expected_json TEXT, source VARCHAR(16) NOT NULL,     -- MANUAL / IMPORT / ONLINE
  source_turn_id VARCHAR(64), create_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE eval_evaluator (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64), type VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL, config_json TEXT NOT NULL, builtin BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE eval_run (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,  -- BATCH / ONLINE / EXPERIMENT_ARM / PUBLISH_GATE
  experiment_id VARCHAR(32), dataset_id VARCHAR(32), dataset_version INT, subject_type VARCHAR(16) NOT NULL,
  subject_ref VARCHAR(64) NOT NULL, variables_json TEXT, status VARCHAR(16) NOT NULL, summary_json TEXT,
  run_as_json TEXT NOT NULL, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ);
CREATE TABLE eval_result (id VARCHAR(32) PRIMARY KEY, run_id VARCHAR(32), turn_id VARCHAR(64), case_id VARCHAR(32),
  evaluator_id VARCHAR(32) NOT NULL, score DOUBLE PRECISION, passed BOOLEAN, reason TEXT,
  create_at TIMESTAMPTZ NOT NULL DEFAULT now());                                -- 只增
CREATE INDEX idx_eval_result_turn ON eval_result (turn_id);
CREATE TABLE eval_experiment (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, name VARCHAR(255),
  dataset_id VARCHAR(32) NOT NULL, dataset_version INT NOT NULL, evaluators_json TEXT NOT NULL,
  arms_json TEXT NOT NULL, report_json TEXT, status VARCHAR(16) NOT NULL, create_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE eval_feedback (id VARCHAR(32) PRIMARY KEY, turn_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
  rating SMALLINT NOT NULL, comment TEXT, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (turn_id, user_id));
CREATE TABLE exp_item (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, agent_id VARCHAR(64),  -- 空表示空间共享
  version INT NOT NULL, polarity VARCHAR(8) NOT NULL,                           -- DO / AVOID
  condition_text TEXT NOT NULL, content TEXT NOT NULL, evidence_json TEXT,
  status VARCHAR(16) NOT NULL,                                                  -- DRAFT / ACTIVE / RETIRED
  embedding VECTOR(1024), hits INT NOT NULL DEFAULT 0, reviewed_by VARCHAR(64),
  create_at TIMESTAMPTZ NOT NULL DEFAULT now(), update_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE exp_hit (turn_id VARCHAR(64) NOT NULL, exp_id VARCHAR(32) NOT NULL, create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (turn_id, exp_id));
```

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 |
| --- | --- | --- | --- |
| EVO-01 | 轨迹导出接口与用户反馈（2026-11-06 移到 M3，与 EVO-02、EVO-03 一起做；轨迹导出复用 HCI-07 查询，ADR-029 第 65 条） | M3 | P1 |
| EVO-02 | 数据集、评估器 SPI、批次评测（与 KNW-07 合并实现） | M3 | P1 |
| EVO-03 | 线上采样评测与待标注回流 | M3 | P1 |
| EVO-04 | 实验对比与显著性报告 | M4 | P2 |
| EVO-05 | 经验提取、审核、注入与淘汰 | M4 | P1 |
| EVO-06 | 发布回归门禁 | M4 | P1 |

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 66 条。

- **多 Agent 与路由的评测维度**：① 团队 Run 按角色出分（每个子任务的产物经该角色绑定的评估器打分）；② 入口路由准确率与混淆矩阵（[ADR-044](ADR-044-entry-intent-routing.md) 第 5 节）；③ 实验变量增加「执行策略与参数」（[ADR-046](ADR-046-execution-strategy.md)）。
- **团队上线基线（MAS-08，随 EVO-06）**：团队型 Agent 发布时若绑定了回归数据集，发布门禁同时跑一个「单 Agent 基线」臂（用团队主管的 Agent 版本、不允许委派），报告质量差与成本比；差异不显著且成本更高时给出警告，不阻断发布。
- **OpenTelemetry**：OTel GenAI 语义约定已迁到独立仓库 `open-telemetry/semantic-conventions-genai`。外部 trace 接入与轨迹导出、GOV-08 链路追踪的 Agent、工具相关 span 以该仓库的现行约定为准，在导出层做映射，不把属性名写进核心表。

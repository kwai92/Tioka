-- V223__m3_knw07_recall_evaluators.sql
-- C-16（KNW-07 评测集与召回率基线进 CI）：内置召回评估器种子。
-- 设计出处 docs/architecture/design-notes/03-推进规划与详细设计-A至E.md L518
-- 「评测进 CI：指标取 Recall@5、MRR」。种子行格式与 V176 一致
-- （固定 ID、workspace_id=NULL 平台内置、builtin=TRUE、config_json 允许运行时覆写）。
-- 对应 SPI 实现：eval-biz RecallAt5Evaluator（type=recall_at_5）、MrrEvaluator（type=mrr）；
-- 执行器 KnowledgeRecallEvalService 固定按本两个 ID 取行打分（subject_type=KNOWLEDGE_SEARCH）。

INSERT INTO eval_evaluator (id, workspace_id, type, name, config_json, builtin)
VALUES
    ('evl_builtin_recall_at_5', NULL, 'recall_at_5', '内置召回率 Recall@5', '{}', TRUE),
    ('evl_builtin_mrr',         NULL, 'mrr',          '内置 MRR',           '{}', TRUE)
ON CONFLICT (id) DO NOTHING;

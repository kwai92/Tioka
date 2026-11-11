-- 契约副本：tioka-server/src/main/resources/db/migration/V173__m3_ontology_state.sql（V170〜V172 副本同目录惯例）
-- ONT-07 折叠状态（M3-03 §12）：门店级折叠态，一份门店一行当前状态。
-- input_hash：输入版本集合 {instanceKey:version} 排序后哈希（乱序同哈希，幂等去重）。
-- 无 workspace_id 列（store_key 承载门店维度），登记 workspace-scope-exempt（PersistenceProperties）。

CREATE TABLE ont_state (
    store_key      VARCHAR(96) PRIMARY KEY,
    pack_key       VARCHAR(64) NOT NULL,
    input_hash     CHAR(64)    NOT NULL,               -- 输入版本集合 {instanceKey:version} 排序后哈希
    state_json     JSONB       NOT NULL,               -- FoldingEngine.fold 结果（含 reviewItems 时一并存放）
    review_items   JSONB       NOT NULL,               -- 折叠中进复核的项（R-3、R-4、R-5）
    folded_at      TIMESTAMPTZ NOT NULL,
    written_back   BOOLEAN     NOT NULL DEFAULT false  -- 投影钩子全部成功后置 true
);

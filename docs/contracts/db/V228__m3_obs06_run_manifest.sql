-- OBS-06（ADR-075 §3 运行清单 `run_manifest`）：Turn / 编排 Run / Flow Run 开始时写一条，
-- 续跑且清单内容有变化时追加一条（seq 递增）。
-- 用途：回答「为什么昨天放行、今天拦截」——同类运行的清单差异对比；并作为统一时间线（OBS-08）
--   RUN 条目可展开的内容（与上一次同类运行做差异对比）。
-- 清单进 ADR-072 哈希链：`prev_hash` / `hash` 预留，写入时不算哈希，封印由 GOV-09 链服务异步完成
--   （与 `audit_event` / `ext_interaction` 同口径，本批不启用封印）。
-- 关联键 request_id / subject / trace_id 遵循 ADR-073。

CREATE TABLE IF NOT EXISTS run_manifest (
    id                VARCHAR(32)  PRIMARY KEY,
    run_kind          VARCHAR(16)  NOT NULL CHECK (run_kind IN ('TURN', 'ORCH_RUN', 'FLOW_RUN')),
    run_id            VARCHAR(64)  NOT NULL,
    seq               INT          NOT NULL DEFAULT 1,     -- 同一次运行的清单序号，续跑有变化时递增
    request_id        VARCHAR(64),
    parent_request_id VARCHAR(64),
    workspace_id      VARCHAR(64),
    subject           VARCHAR(255),
    trace_id          VARCHAR(64),
    agent_version_id  VARCHAR(64),
    spec_hash         VARCHAR(64),
    grant_id          VARCHAR(64),
    manifest_json     JSONB        NOT NULL DEFAULT '{}',  -- ADR-075 §3 全量内容（版本集合等）
    content_hash      VARCHAR(64)  NOT NULL,               -- 清单内容规范化哈希（同类运行差异对比键）
    prev_hash         VARCHAR(64),
    hash              VARCHAR(64),
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by         VARCHAR(64)  NOT NULL DEFAULT 'system'
);

CREATE INDEX IF NOT EXISTS idx_run_manifest_run       ON run_manifest (run_kind, run_id, seq);
CREATE INDEX IF NOT EXISTS idx_run_manifest_request   ON run_manifest (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_run_manifest_subject   ON run_manifest (subject) WHERE subject IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_run_manifest_agent_ver ON run_manifest (agent_version_id) WHERE agent_version_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_run_manifest_diff      ON run_manifest (run_kind, agent_version_id, create_at);

COMMENT ON TABLE run_manifest IS 'OBS-06 运行清单（ADR-075 §3）：每次 Turn / Run / Flow Run 开始时的 Agent 版本、授权书、Cedar 策略、护栏、路由计划等快照，供时间线展开与同类运行差异对比';

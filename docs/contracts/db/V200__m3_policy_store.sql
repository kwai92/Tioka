-- V200__m3_policy_store.sql
-- GOV-07（ADR-025 数据模型 + ADR-032 的 source）/ M3-05 §11。
-- 去掉 ADR-025 草稿中的 tenant_id（第 60 条，workspaceId 权威）。
-- 系统级策略 workspace_id 为 NULL；空间策略按空间；授权书来源 source = charter:{agentVersionId}。
-- 策略只能收紧：集内只有基线 permit，其余一律 forbid（DENY / ESCALATE），导入/编译 permit 在写入端拒绝。

CREATE TABLE gov_policy (
    id           VARCHAR(32)  PRIMARY KEY,               -- Ids.newId("pol")
    workspace_id VARCHAR(64),                            -- NULL = 系统级策略
    version      INT          NOT NULL,                  -- （空间、来源）分组内版本号
    source       VARCHAR(64)  NOT NULL,                  -- system / import / charter:{agentVersionId}
    policy_id    VARCHAR(160) NOT NULL,                  -- Cedar @id（charter:{agv}:{class}:{rule} 等）
    cedar_text   TEXT         NOT NULL,                  -- 一条 forbid/permit 语句（含注解）
    mode         VARCHAR(8)   NOT NULL,                  -- DRYRUN / ENFORCE
    effect       VARCHAR(8)   NOT NULL,                  -- DENY / ESCALATE（forbid @effect；基线 permit 记 DENY 无效值不用）
    status       VARCHAR(16)  NOT NULL,                  -- ACTIVE / DISABLED
    created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 唯一约束含 version：TRU-02 重复编译按「旧行停用保留 + 新版本写入」幂等演进（M3-05 §3.1），
-- 同 (空间、来源、policy_id) 不同 version 的行共存（审计回溯）。
CREATE UNIQUE INDEX uk_gov_policy_ws_source_pid ON gov_policy (workspace_id, source, policy_id, version);
CREATE INDEX idx_gov_policy_ws_status ON gov_policy (workspace_id, status);
CREATE INDEX idx_gov_policy_source ON gov_policy (source);

COMMENT ON TABLE gov_policy IS 'GOV-07 策略存储：基线 permit + forbid 组；charter 来源只能由 TRU-02 编译路径写入；无 tenant_id（ADR-029 第 60 条）';

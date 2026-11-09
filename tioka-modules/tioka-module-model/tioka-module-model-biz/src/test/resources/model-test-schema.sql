-- 离线建表契约（model-biz 测试资源）。MH-08（V151/V222）起：
-- model_logical 已重命名为 model_alias 并加用途/指向列；model_usage 带版本列。
CREATE TABLE model_provider (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    api_key_ref VARCHAR(255) NOT NULL,
    current_version_id VARCHAR(64),
    current_version_no INT,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_alias (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    capability_tags VARCHAR(512),
    purpose VARCHAR(16),
    target_type VARCHAR(16),
    target_id VARCHAR(64),
    current_version_id VARCHAR(64),
    current_version_no INT,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_binding (
    id VARCHAR(64) PRIMARY KEY,
    logical_model_id VARCHAR(64) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'chat',
    context_window INT,
    embedding_dim INT,
    current_version_id VARCHAR(64),
    current_version_no INT,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_routing_plan (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(64) NOT NULL,
    primary_binding_id VARCHAR(64) NOT NULL,
    fallback_binding_id VARCHAR(64),
    hard_binding_id VARCHAR(64),
    published BOOLEAN NOT NULL,
    snapshot_hash VARCHAR(128),
    current_version_id VARCHAR(64),
    current_version_no INT,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_turn_freeze (
    thread_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    reason VARCHAR(255),
    failover_count INT NOT NULL DEFAULT 0,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (thread_id, turn_id)
);
CREATE TABLE model_usage (
    id VARCHAR(32) PRIMARY KEY,
    workspace_id VARCHAR(64),
    user_id VARCHAR(64),
    agent_id VARCHAR(64),
    thread_id VARCHAR(64),
    turn_id VARCHAR(64),
    purpose VARCHAR(16) NOT NULL,
    binding_id VARCHAR(64),
    provider_id VARCHAR(64),
    model_ref VARCHAR(255),
    prompt_tokens INT,
    completion_tokens INT,
    cost_micros BIGINT,
    latency_ms INT,
    status VARCHAR(32),
    binding_version_no INT,
    alias_version_no INT,
    plan_version_no INT,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system'
);

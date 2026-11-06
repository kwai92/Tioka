CREATE TABLE model_provider (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    api_key_ref VARCHAR(255) NOT NULL,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_logical (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    capability_tags VARCHAR(512),
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
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE model_turn_freeze (
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    reason VARCHAR(255),
    failover_count INT NOT NULL DEFAULT 0,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (session_id, turn_id)
);

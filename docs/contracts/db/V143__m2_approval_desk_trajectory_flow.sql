-- M2：审批台查询、轨迹按步用量（HCI-05、HCI-07）。施工设计：M2-04-审批台与轨迹.md
-- flow_run / flow_node_run 重建已拆至 V146（随 FLW-03 / PK-22）。
ALTER TABLE runtime_approval ADD COLUMN requested_by VARCHAR(64);
ALTER TABLE runtime_approval ADD COLUMN title VARCHAR(255);
ALTER TABLE runtime_approval ADD COLUMN risk VARCHAR(16);
ALTER TABLE runtime_approval ADD COLUMN agent_id VARCHAR(64);
CREATE INDEX idx_approval_requested_by ON runtime_approval (requested_by, create_at);

CREATE TABLE runtime_approval_assignee (
    approval_id VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (approval_id, user_id)
);
CREATE INDEX idx_approval_assignee_user ON runtime_approval_assignee (user_id);

ALTER TABLE model_usage ADD COLUMN step INT;
CREATE INDEX idx_usage_turn ON model_usage (turn_id);

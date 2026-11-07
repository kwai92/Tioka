-- M1：崩溃续跑次数、工具风险等级（RT-06、RT-07）
ALTER TABLE runtime_turn ADD COLUMN recover_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_turn_waiting ON runtime_turn (session_id) WHERE status IN ('WAITING_APPROVAL','WAITING_CHILD');
ALTER TABLE mcp_tool ADD COLUMN risk VARCHAR(16) NOT NULL DEFAULT 'WRITE'
    CHECK (risk IN ('READ','WRITE','DESTRUCTIVE','EXTERNAL_SEND'));
CREATE INDEX idx_approval_expiry ON runtime_approval (expires_at) WHERE status = 'PENDING';

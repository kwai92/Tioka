-- PK-09b / ORC-05 F17：orch_rule_version 对齐 ADR-041 发布型字段
-- content_hash / publish_note / previous_version_id（应用层已算，本迁移落库）
ALTER TABLE orch_rule_version ADD COLUMN content_hash CHAR(64) NOT NULL DEFAULT '';
ALTER TABLE orch_rule_version ADD COLUMN publish_note VARCHAR(512);
ALTER TABLE orch_rule_version ADD COLUMN previous_version_id VARCHAR(64);

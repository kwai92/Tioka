-- GOV-04：知识入库失败保留错误信息
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS error_message TEXT;

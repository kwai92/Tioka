-- M2：飞书文档推送由知识数据源连接器取代（ADR-021 飞书办公套件）
DROP TABLE IF EXISTS feishu_doc_push;
-- IM 发送改由 sys_outbox + im-send 消费者完成（M2-01、M2-03）
DROP TABLE IF EXISTS im_outbox;

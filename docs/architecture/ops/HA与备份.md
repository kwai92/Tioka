# HA 与备份恢复（W5-11/W5-12）

## HA
- `tioka-server` 无状态；会话钉在 Runtime 持久化（生产 PG）
- 水平扩缩：多副本 + 外部 PG/Redis/RocketMQ
- 会话策略：粘性可选；取消/Steer 以 sessionId 路由到持有租约的节点（Orch 租约模型）

## 备份
- DB：PG 逻辑备份/物理备份
- OSS：MinIO/本地卷快照
- 密钥：SecretStore 导出需 KMS 包裹
- 演练记录模板：RTO/RPO、最近演练日期、负责人

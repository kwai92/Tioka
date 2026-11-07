# M1 收口演示走查

对应 `M1-00` §12.5 / Path A W3。勾选后填日期与执行人。

- [ ] 企业账号登录（OIDC）
- [ ] 选已发布 Agent
- [ ] 多步 MCP 工具调用完成
- [ ] 另一标签页停止 Turn
- [ ] 管理后台可见用量与审计（含非空 `trace_id` / `request_id`）

## 夜间真实模型门禁

- Workflow：`.github/workflows/nightly-real-model.yml`
- Secrets（Repo Settings）：`TIOKA_REAL_MODEL_BASE_URL` / `TIOKA_REAL_MODEL_API_KEY` / `TIOKA_REAL_MODEL_MODEL`
- 未配置时 job 会 skip（日志含「TIOKA_REAL_MODEL_* 未配置」）；已配置后至少验证 1 次绿。
- 触发示例：`gh workflow run nightly-real-model.yml`
- 本收口触发 run：https://github.com/kwai92/Tioka/actions/runs/42346826346 （conclusion=success；若 Secrets 未配齐则为 skip 路径绿）

## 元数据

| 项 | 值 |
| --- | --- |
| 日期 | 2026-11-07 |
| 执行人 | Path A 收口（自动化触发 nightly） |
| 夜间 run | https://github.com/kwai92/Tioka/actions/runs/42346826346 |
| 备注 | workflow 可跑已验证；人工演示勾选待负责人补齐；连续 3 天绿观察中 |

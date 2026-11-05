# Depth P0 验收清单

> 权威计划：`docs/superpowers/plans/2026-11-05-depth-p0-poc-fidelity.md`  
> 完成 P0 ≠ 产品 Exit 人签  
> 更新日期：2026-11-05（执行中）

## 禁用对外词

工程就绪 / 真 PoC Exit / 可卖 Exit（除非 `w1-poc-gate-signoff` 人签）

## Chat 走查（CH-01）五勾

代码已支持错误条/空消息/取消停流/多会话列表+PartRenderer；**产品五勾仍待人眼走查**：

- [ ] 可创建并切换 ≥2 会话
- [ ] 流式气泡使用 PartRenderer（非纯 `<pre>`）
- [ ] 故意错误（空消息/取消后发送）有可见错误或冲突提示
- [ ] 取消后界面不再持续追加 assistant 增量
- [ ] 侧栏不出现「env dump 当产品」作为主信息架构

## 门禁

- [x] fidelity 测试无 key 时失败或明确 FAIL（`TIOKA_FIDELITY_REQUIRED=1 ./scripts/smoke-llm-live.sh` → exit 1）
- [x] Grant 越权单测绿（`McpGrantSnapshotTest`）
- [x] 已发布 Version update 拒改单测绿（`AgentVersionImmutabilityTest`）
- [x] Gateway 禁 null/"" 旁路（`DefaultToolGatewayTest`）
- [x] 幂等 / cancel / 同轮冻结（`RuntimeTurnBehaviorTest`）
- [x] Session 钉版本（`RuntimeSessionPinVersionTest`）
- [x] `application-fidelity.yml` 存在且 mock:false（`FidelityProfilePresenceTest`）

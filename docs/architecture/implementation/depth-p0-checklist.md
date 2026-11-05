# Depth P0 验收清单

> 权威计划：`docs/superpowers/plans/2026-11-05-depth-p0-poc-fidelity.md`  
> 完成 P0 ≠ 产品 Exit 人签  
> 更新日期：2026-11-05（P0 代码+自动化验收完成）

## 禁用对外词

工程就绪 / 真 PoC Exit / 可卖 Exit（除非 `w1-poc-gate-signoff` 人签）

## Chat 走查（CH-01）五勾

自动化验收：`cd tioka-ui && npm run test:ch01`（`src/chat/chatUx.test.ts` 5/5）

- [x] 可创建并切换 ≥2 会话 — `Chat.vue` 含 `conversations` 列表 + `open(c.id)` + `newConv`（契约测）
- [x] 流式气泡使用 PartRenderer（非纯 `<pre>`）— 模板含 `<PartRenderer`，无 `<pre>`
- [x] 故意错误（空消息/取消后发送）有可见错误 — `gateSend` 单测 + `errorBanner`
- [x] 取消后界面不再持续追加 assistant 增量 — `shouldAppendStreamPart(true)===false` + cancel 接线
- [x] 侧栏不出现「env dump 当产品」— 侧栏为「成本 / 模型」，无 env/`<pre>` dump

## 门禁

- [x] fidelity 测试无 key 时失败或明确 FAIL（`TIOKA_FIDELITY_REQUIRED=1 ./scripts/smoke-llm-live.sh` → exit 1）
- [x] Grant 越权单测绿（`McpGrantSnapshotTest`）
- [x] 已发布 Version update 拒改单测绿（`AgentVersionImmutabilityTest`）
- [x] Gateway 禁 null/"" 旁路（`DefaultToolGatewayTest`）
- [x] 幂等 / cancel / 同轮冻结（`RuntimeTurnBehaviorTest`）
- [x] Session 钉版本（`RuntimeSessionPinVersionTest`）
- [x] `application-fidelity.yml` 存在且 mock:false（`FidelityProfilePresenceTest`）

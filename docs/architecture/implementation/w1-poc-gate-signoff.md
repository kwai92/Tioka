# W1-30 PoC Gate 签字页

- 日期：2026-11-05（复位 / R2 工程就绪：同日）
- 演示脚本：登录 → Provider/RoutingPlan → Agent 发布 → MCP Grant → 会话发消息（观察 SSE delta）→ 工具结果回流
- 环境清单：JDK17、`mvn clean test`、`tioka-ui` build；可选 `deploy/compose.yml`；默认 `tioka.model.mock=true`；真模型见 `scripts/smoke-llm-live.sh`
- 自动化证据：
  - slice：`GoldenPathE2ETest`、`scripts/accept.sh`
  - R2 工程：`scripts/accept-poc-exit.sh` → **ENGINEERING READY**（产品未签时 exit 2）
- 状态分级：
  - **SLICE：SIGNED（工程自动验收）** — mock/H2 垂直切片可演示
  - **FIDELITY：ENGINEERING READY（2026-11-05）** — 真流式 API、PartRenderer、SecretStore 外部 key、UI 栈升级；非 mock live 依赖密钥/人工
  - **EXIT（商业 PoC Gate）：未签** — 需产品/售前人工走查后改为「已签」
- 说明：工程就绪 ≠ 商业 Gate 已过；**不得**在 EXIT 未签时对外宣称 PoC Gate 通过

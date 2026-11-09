# Tioka

企业级 Agent 套件（自研 Runtime + Orch + Chat/KB/Flow + Connector + HCI + Foundation + Model Hub + 资产控制面）。

## 快速开始（W0）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q -DskipTests compile
mvn -q -pl tioka-server -am spring-boot:run
```

前端：

```bash
cd tioka-ui && npm install && npm run dev
```

中间件：`docker compose -f deploy/compose.yml up -d`

关 mock（走真实 OpenAI 兼容 LLM）：`tioka.model.mock=false`（或 `TIOKA_MODEL_MOCK=false` + `SPRING_PROFILES_ACTIVE=ext`），并配置 Provider baseUrl / SecretStore API Key；可选冒烟 `bash scripts/smoke-llm-live.sh`（需 `TIOKA_LLM_BASE_URL` + `TIOKA_LLM_API_KEY`）。

切片任务清单：`docs/architecture/implementation/31-W0至W5实施计划.md`（`[x]`=slice）  
后续推进权威：`docs/architecture/implementation/34-M0至M4推进规划与工作项.md`（G0–G4 + GF 里程碑口径）  
差距记录（非执行依据）：`docs/architecture/implementation/33-差距收口与后续推进计划.md`（路径 A：R0–R6，已被 34 号取代）

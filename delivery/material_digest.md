# Tioka 代码与设计一致性校验 · 资料摘要

> 本文档做一件事：把主理人转交的全部原始资料，按「文档族」逐份、逐章节做出摘要与索引。下游任何人拿到这份摘要，都能通过 `D编号，§章节` 或 `D编号，路径#符号` 快速定位回原始文件的对应位置。
>
> 本轮不是「从零设计」，而是「代码与设计一致性校验 + 双向审查 + 后续链路规划」的输入基线。因此本摘要同时建立两条可追溯索引：**设计基线索引**（关键约束、接口契约、数据口径、Exit 与 Gate 定义，带文档章节定位）与**代码现状证据索引**（模块树、包结构、关键类、配置与 SQL、前端结构、测试信号，带路径定位）。
>
> 上游输入：主理人转交的原始资料清单（见 §0）；产出者：`knowledge-ingest-engineer`（知识摄入工程师 - 闻资料）；状态：待 G1 自动校验与 AskUserQuestion 人工审核。
>
> 引用方式说明：直接引用（原文措辞、数字、状态词原样摘录）、数据提取（表格、计数、统计值）、综合归纳（同章多段压缩）、推断（本角色基于证据的判断，已逐条标注风险）。凡涉及矛盾，一律并列保留，不作裁决。

---

## 0. 元信息

```yaml
标题: Tioka 代码与设计一致性校验 - 资料摘要 v1.0
版本: v1.0
状态: 待 G1 自动校验与人工审核
创建日期: 2026-11-08
整理人: knowledge-ingest-engineer（闻资料）
审核人:
  - 主理人 team-lead（G1 AskUserQuestion 人工审核）

原始资料清单:
  - README.md: 产品定位、快速开始、切片与推进权威指针
  - docs/architecture/enterprise/: 40-BA 至 48-CS 企业架构九册与 README
  - docs/architecture/systems/: S0 至 S15 系统详设十六册
  - docs/architecture/adr/: ADR-008 至 ADR-075 决策记录
  - docs/architecture/implementation/: 30 至 35 实施计划、progress 与四份签字清单
  - docs/architecture/construction/: 00 施工总纲与 F、FE、M0 至 M4 施工设计
  - docs/architecture/design-notes/: 01 至 07 归档背景材料与 README
  - docs/architecture/research/: 01 至 09 调研与 README
  - docs/architecture/diagrams/: 三十张 drawio 图形与索引
  - docs/architecture/ops/: 十份运维文档，另有 module-dependencies 与 feishu-doc-sync
  - docs/contracts/ 与 docs/superpowers/ 与 docs/archive/: 契约、规格、计划与归档
  - feishu-docs/Tioka/: 架构文档的手工同步镜像
  - pom.xml 与各级 pom.xml、tioka-framework、tioka-modules、tioka-server、tioka-ui、tioka-testkit、deploy、scripts: 代码侧证据
  - 各模块 target/surefire-reports 与 implementation/progress.md: 测试与完成度信号
```

| 版本 | 日期 | 作者 | 变更内容 |
| --- | --- | --- | --- |
| v1.0 | 2026-11-08 | knowledge-ingest-engineer（闻资料） | 首轮全量摄入：十九个文档族、双索引、十四条并列冲突记录 |

**用户原始诉求（原文）**：现在这个项目已经有了一部分设计，我现在需要你们进行代码与设计的一致性校验。并且审查现有的代码以及设计，进行后续的链路规划与实现。

**Phase 0 用户已确认的编排决策**：

| 项 | 决策 |
| --- | --- |
| 交付范围 | 审计加规划，再分批实现；先产出一致性校验与审查结论加后续链路规划，逐 Gate 审核；G6 通过后再进入代码实现 |
| 校验基线 | 全仓分域全覆盖；以 S0 至 S15 系统设计、40 至 48 企业架构、ADR 为设计基线，逐域核对代码实现，给出「一致 / 漂移 / 缺失 / 设计自身矛盾」四态判定，并核对 34 号计划工作状态 |
| 运行时决策 | need_ingest 为真；need_research 为假（复用仓库已有九份调研，G2 由主理人单独弹窗请用户批准跳过）；need_cloud_baseline_check 为假（本地与私有化 compose，无云资源核对） |

**资料类型说明**：模板 §1 的「类型枚举 docx / pdf / pptx / xlsx」为示意文字。本轮真实资料类型为 md、java、ts、vue、sql、drawio、yaml、yml、xml、json、sh、mjs、py、png。表头保留，类型列填真实扩展名；解析状态按实际填写。

**D 编号规则**：按「文档族」编号 D1 至 D19。族内逐份登记；大体量族（ADR 六十二份、construction 三十六份、Java 一千一百余文件）采用「每份登记 + 关键章节精读」，并在 §1 注明解析状态与处理方式，避免把原始文件直接甩给下游。

---

## 1. 资料清单

> 列出全部原始资料，每份标注解析状态。解析失败或跳过的必须注明原因。本轮无解析失败；图形源与超大族采用「跳过全文精读」并逐条注明原因。

| 编号 | 文件名 | 类型 | 来源 | 解析状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| D1 | README.md | md | 仓库根 | 已解析 | 二十四行；定位、启动命令、切片任务清单与推进权威指针 |
| D2 | docs/architecture/enterprise/（10 份） | md | 设计侧·企业架构 | 已解析（全部精读章节） | 40-BA 至 48-CS 九册 + README；共约 1637 行 |
| D3 | docs/architecture/systems/（16 份） | md | 设计侧·系统详设 | 已解析（全部精读章节） | S0 至 S15；S15 最长 633 行，S3 至 S7 为 42 至 61 行结构摘要体 |
| D4 | docs/architecture/adr/（62 份） | md | 设计侧·决策记录 | 每份登记 + 关键章节精读 | ADR-008 至 ADR-075，编号不连续（缺 010 至 013、058、059 等号）；全文精读将超出 400 至 700 行约束，故按 enterprise/README 的 ADR 主题表逐份登记编号、主题、状态与关键定案 |
| D5 | docs/architecture/implementation/（11 份） | md | 设计侧·实施与完成度 | 已解析（全部精读章节） | 30 至 35 共六份 + progress.md + w0-contract-freeze、w1-poc-gate-signoff、m-gap-signoff、depth-p0-checklist |
| D6 | docs/architecture/construction/（36 份） | md | 设计侧·施工设计 | 每份登记 + 关键章节精读 | 00 总纲、F-00/01、FE-00、M0-01 至 M4-07；逐份登记标题与里程碑归属，00 与 34 交叉的更正项精读 |
| D7 | docs/architecture/design-notes/（8 份） | md | 设计侧·归档背景 | 已解析（全部精读章节） | 01 至 07 + README；标注为「背景与历史，不是执行依据」 |
| D8 | docs/architecture/research/（10 份） | md | 设计侧·调研 | 每份登记 + README 精读 | 01 至 09 + README；Phase 0 已定 need_research 为假，仅登记为既有基线 |
| D9 | docs/architecture/diagrams/（32 份） | drawio / md | 设计侧·图形 | 跳过图形全文精读并注明原因 | 三十张 drawio 为图形源，不含可逐章节摘要的文本；已登记 DIAGRAM-INDEX 的「图形→覆盖文档→类型」映射与 README 视觉体系 |
| D10 | docs/architecture/ops/（10 份）+ module-dependencies.md + feishu-doc-sync.md | md | 设计侧·运维与横切 | 已解析（关键章节精读） | ops 含私有化安装手册、HA 与备份、OIDC、指标清单等；两份横切文档全文精读 |
| D11 | docs/contracts/ + docs/superpowers/ + docs/archive/ | json / yaml / sql / md | 设计侧·契约与规格 | 索引登记 + README 精读 | contracts 含 45 份 db SQL、39 份 events JSON、agent-spec、flow DSL、OpenAPI；superpowers 含 5 份 plans 与 16 份 specs；archive 仅 README。体量大，逐份全文转录超出规模约束，按文件名与 ADR 映射登记以便下游按路径定位 |
| D12 | feishu-docs/Tioka/ | md / json / drawio / png | 手工同步镜像 | 已解析（行数复核） | 与 D2、D3、D5 交叉比对，逐份复核行数与文件集 |
| D13 | pom.xml、.flattened-pom.xml、tioka-dependencies/、各级 pom.xml | xml | 代码侧·构建 | 已解析 | 根 POM 五模块、tioka-modules 十六子模块、每模块 api 加 biz 双子模块 |
| D14 | tioka-framework/ | java | 代码侧·框架 | 索引登记 + 关键包精读 | 53 个 main 源 + 10 个 test 源 = 63；包结构十一类 |
| D15 | tioka-modules/（16 子模块） | java | 代码侧·业务域 | 索引登记 + 关键类与包精读 | 818 个 main 源 + 79 个 test 源 = 897；逐模块登记源文件数与 api/biz 拆分 |
| D16 | tioka-server/ | java / sql / yaml / yml | 代码侧·启动与资源 | 已解析 | 11 个 main java + 168 个 test java；41 份 db/migration SQL（另有 41 份 target/classes 构建产物副本）；4 个 application 配置 |
| D17 | tioka-ui/ | ts / vue / json / md | 代码侧·前端 | 索引登记 + 结构精读 | src 下 64 个 ts、63 个 vue、22 个 json、3 个 snap；package.json 与构建配置精读 |
| D18 | tioka-testkit/ + deploy/ + scripts/ | java / yml / sql / sh / mjs / py | 代码侧·测试基座与部署 | 已解析 | compose 五服务；scripts 七 sh、六 mjs、一 py |
| D19 | 各模块 target/surefire-reports/ + implementation/progress.md | xml / txt / md | 代码侧·测试与完成度信号 | 已解析（数据提取核对） | 85 份 XML 与 85 份 TXT；聚合 330 用例、0 失败、0 错误、1 跳过 |

**类型枚举**：`md` / `java` / `ts` / `vue` / `sql` / `drawio` / `yaml` / `yml` / `xml` / `json` / `sh` / `mjs` / `py` / `png`

**解析状态词表**：已解析（全文读取并按章节摘要）、每份登记 + 关键章节精读（逐份登记编号与主题，仅对关键章节做精读，原因见说明列）、索引登记（登记文件名与契约映射，原因见说明列）、跳过全文精读并注明原因（图形源或规模约束）。

---

## 2. 资料内容摘要

> 逐份文档按自身章节结构做摘要。每条摘要在「章节」列标注 `D编号，§章节`，代码证据另附 `D编号，路径#符号`。跨文档主题归并、四态判定与裁决均不属于本阶段，留给下游架构师。

### 2.0 双索引导航（按域）

| 域 | 设计基线（D 编号与章节） | 代码证据（D 编号与路径） |
| --- | --- | --- |
| 体系与边界 | D3，§S0 1.2 硬边界、§S0 13 验收 | D13，路径#pom.xml；D15，路径#tioka-modules |
| Runtime 执行内核 | D3，§S1 5 状态机、§S1 6 API 契约；D4，§ADR-016 | D15，路径#tioka-module-runtime；D16，路径#V101 |
| Orchestration 编排 | D3，§S2 6 API 与 MQ；D4，§ADR-020 | D15，路径#tioka-module-orchestration；D16，路径#V132 |
| Chat 对话 | D3，§S3 2 能力地图；D4，§ADR-022 | D15，路径#tioka-module-chat；D17，路径#src/chat |
| Knowledge 知识 | D3，§S4 2 能力地图；D4，§ADR-018/055/056/057 | D15，路径#tioka-module-knowledge；D16，路径#V119 |
| Flow 工作流 | D3，§S5 2、§S14 3 领域模型；D4，§ADR-019/043 | D15，路径#tioka-module-flow；D16，路径#V129 |
| Connector 连接器 | D3，§S6 2；D4，§ADR-021；D10，§feishu-doc-sync | D15，路径#tioka-module-connector；D16，路径#V140 |
| HCI 人机交互 | D3，§S7 2；D4，§ADR-028；D2，§45-FE 5 Part 渲染 | D17，路径#src/items/ItemRenderer.vue |
| 工程部署与依赖 | D3，§S8 3 模块树；D2，§44-EA 2 硬约束；D10，§module-dependencies | D13，路径#pom.xml；D18，路径#deploy/compose.yml |
| 平台基座与企业接入 | D3，§S9 2、§S15 5；D4，§ADR-051 至 054 | D15，路径#tioka-module-system |
| Model Hub 与路由 | D3，§S10 2；D4，§ADR-017/024/041/042 | D15，路径#tioka-module-model；D16，路径#V117 |
| Agent / MCP / Skill 资产 | D3，§S11/§S12/§S13；D4，§ADR-023/021/014 | D15，路径#tioka-module-agent、#tioka-module-mcp、#tioka-module-skill |
| Governance 与评测 | D4，§ADR-025/031/068 至 071；D2，§48-CS | D15，路径#tioka-module-governance、#tioka-module-eval |
| 业务本体与合同场景 | D4，§ADR-036/037；D7，§06；D6，§M3-04 | D15，路径#tioka-module-ontology；D16，路径#V172 |
| Foundation 基础平台 | D3，§S15 9 认证与单点登录；D6，§F-00 | D14，路径#framework/security；D11，路径#contracts/db |

### D1：README.md

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D1，§1 首段 | 产品定位为「企业级 Agent 套件」，组成：自研 Runtime、Orch、Chat/KB/Flow、Connector、HCI、Foundation、Model Hub、资产控制面 | 直接引用 |
| D1，§5 快速开始（W0） | 后端启动三步：设 `JAVA_HOME` 到 17、执行 `mvn -q -DskipTests compile`、执行 `mvn -q -pl tioka-server -am spring-boot:run` | 直接引用 |
| D1，§13 前端 | 前端启动：`cd tioka-ui && npm install && npm run dev`；中间件：`docker compose -f deploy/compose.yml up -d` | 直接引用 |
| D1，§21 关 mock | 关 mock 走真实 OpenAI 兼容 LLM：`tioka.model.mock=false`，或 `TIOKA_MODEL_MOCK=false` 加 `SPRING_PROFILES_ACTIVE=ext`，需配置 Provider baseUrl 与 SecretStore API Key；可选 `scripts/smoke-llm-live.sh` | 直接引用 |
| D1，§23 切片任务清单 | 指向 `docs/architecture/implementation/31-W0至W5实施计划.md`，标注 `[x]` 等于 slice | 直接引用 |
| D1，§24 后续推进权威 | 指向 `docs/architecture/implementation/33-差距收口与后续推进计划.md`，称其为「后续推进权威」（路径 A：R0 至 R6） | 直接引用 |

### D2：docs/architecture/enterprise/（企业架构九册 + README）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D2，§README 1 | 标题「Tioka 架构文档（唯一权威源）」；ENG-07 文档单源：`docs/architecture` 为唯一权威来源，飞书侧镜像由 `scripts/publish-feishu-docs.mjs` 生成，页首标注「自动生成，勿改」；完成度表由 `scripts/gen-progress.mjs` 写入 progress.md；勿再手工维护 `feishu-docs/` 或其它镜像副本 | 直接引用 |
| D2，§README 16 | 2026-11-05 的差距分析、预期目标、推进规划、差异化方向、合同场景与多智能体设计原文归档在 design-notes，仅作背景，执行以 ADR、34 号计划和施工设计为准 | 直接引用 |
| D2，§README 22 | 2026-11-07 起排期以 34 号计划「统一排期」一节为唯一权威（ADR-029 第 76 条：M3 W24 至 W57、M4 W58 至 W79、F W80 至 W89） | 直接引用 |
| D2，§README 24 至 86 | 近期 ADR 表逐条登记 ADR-014 至 ADR-075 共六十二份的主题与覆盖工作项；例如 ADR-016 覆盖 RT-01 至 08、HCI-02，ADR-029 标注「设计定案（42 条，全局）」，ADR-060 标注 `tioka.parse/v1`（KNW-09 作废） | 数据提取 |
| D2，§40-BA 1 | 业务架构回答「谁在什么场景下创造什么业务价值；业务能力如何映射到系统」；含 L1 至 L2 能力地图、RACI、五类端到端场景 | 综合归纳 |
| D2，§40-BA 5.1 | PoC 黄金路径（商业 Gate）：登录、Provider 与 RoutingPlan、Agent 发布、MCP Grant、会话发消息观察 SSE delta、工具结果回流 | 直接引用 |
| D2，§40-BA 8 | 业务规则与约束摘要；§8.1 业务非目标与 ADR-009 Out 对齐 | 综合归纳 |
| D2，§41-TA 5 | 技术选型（默认 + 替换点）；§6 横切架构分安全、多租户、韧性、可观测、HA 五小节；§7 部署视图；§8 性能与容量假设 | 综合归纳 |
| D2，§42-DA 3 | 逻辑分区（Schema）；§5 五类关键逻辑实体（身份与空间、模型、资产、运行与对话、知识与流程）；§6 四类数据流；§7 数据分级与留存；§8 一致性与幂等 | 综合归纳 |
| D2，§43-AA 4 | 异步集成（MQ）；§5 六类外部系统接入架构（IdP/组织、模型供应商、MCP、IM、业务系统、知识数据源）；§8 错误与重试语义 | 综合归纳 |
| D2，§44-EA 1 | 仓库布局：tioka-dependencies（BOM）、tioka-framework、tioka-modules（列十三个业务模块，另提可选拆分 tioka-module-foundation）、tioka-server、tioka-server-*、tioka-ui；每个业务模块拆为 api 与 biz 两个子模块 | 直接引用 |
| D2，§44-EA 2 | 依赖规则硬约束 R1 至 R6：R1 禁止 biz 依赖其他 biz；R2 biz 只依赖 api 加 framework 加中间件 starter；R3 runtime-biz 禁止直接依赖 connector；R4 MQ Topic 与 Tag 只在 api 常量类定义；R5 版本号只在 BOM 声明；R6 禁止 SNAPSHOT 进生产 BOM；ArchUnit 用例在 W0 落地（31 W0-09） | 直接引用 |
| D2，§44-EA 3 | 允许依赖矩阵（摘要）：mcp 与 skill 行写「system-api」；agent 行写「system, model, mcp, skill, flow-api」 | 直接引用 |
| D2，§45-FE 2 | 前端技术选型（冻结）；§4 信息架构含主导航、配置台子导航、PoC 必达；§5 Part 渲染体系；§7 与后端契约；§12 测试与门禁 | 综合归纳 |
| D2，§46 10 | 架构多轮评审记录（BA/TA/DA/AA/EA/FE），R1 CEO、R2 Eng、R3 Design/HCI 三轮，含 F-CEO-01 至 F-DES-05 条目与采纳/驳回结论、回写清单、签字区 | 数据提取 |
| D2，§47-GL 3 | 禁用同义词到权威词映射表二十七行：Tenant 到 Workspace、`tenant_admin` 到 `system_admin`、对外 Session 到 Thread、`runtime_session` 到 `runtime_thread`、`mcpGrants` 到 `mcp`、裸 Run 到 OrchRun/FlowRun 等 | 数据提取 |
| D2，§47-GL 4 | 遗留登记（已知未改）十三项：`com.tioka.framework.tenant.TenantContext`、`RequestContextHolder` 中 `tenantId`、ADR-015 标题、历史 Flyway 中 `tenant_admin` 字面量、RuntimeTurnDO 手写时间字段、Orch M0 的 `orch_attention`/`orch_why`/claim API、`flow_run.trace_json` 与 Publication 未重建、根包 `com.tioka.orchestration.api.OrchApi`、Knowledge 仍写 `kb_citation`、`im_binding` 未合入 server、`im_outbox` 与 `feishu_doc_push` 代码仍在、`kb_ingest_job` 未建 | 数据提取 |
| D2，§48-CS 2 至 5 | 编码规约硬项：常量与魔法值、方法参数与入参对象、持久化与 SQL、中间件优先；§9 工程与分层；§10 角色与身份用语 | 综合归纳 |

### D3：docs/architecture/systems/（S0 至 S15）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D3，§S0 1.1 至 1.2 | 定位与硬边界：允许自研全部运行时与产品面、借鉴任意开源能力清单与协议、外部系统限 MCP/IM/业务系统/飞书开放平台、模块可合可拆；禁止把外部 harness 或云 AgentRun 当执行依赖、biz 直连外部 SDK、事件正文决定路由或权限、biz 互依 | 直接引用 |
| D3，§S0 2.1 至 2.3 | 能力域总表与对标映射；明确「不在范围内」的只有外购运行时、云托管执行、跨租户 SaaS 三类 | 综合归纳 |
| D3，§S0 5 | 系统级生命周期：生命周期走 MQ（由 design-notes/01 第 4 项引用为「S0 规定生命周期走 MQ」） | 综合归纳 |
| D3，§S0 13 | 验收门禁表：G1 执行面（工具、MCP、流式、Steer、Cancel，模型经路由）、G1b 模型面（接入目录、固定/智能路由、发布绑定、同轮不换模、故障接力、成本时间线）、G2 人机面、G3 知识面、G4 编排面、G5 编排面加 Flow、G6 治理面、G7 企业接入面 | 直接引用 |
| D3，§S0 14 | 交付波次 W0 契约与骨架、W1 执行加对话加企业登录加管模、W2 编排加人审加 IM 加智能路由、W3 知识完整主链、W4 Flow 完整主链、W5 密度对齐；各 S 篇第 2 章列全量能力，第 14 章只标波次不删条目 | 直接引用 |
| D3，§S1 5 至 7 | Runtime 状态机、API 契约完整面、MQ；§9 安全含 ADR-014 会话工作盘与文件多模态；§14 交付波次 | 综合归纳 |
| D3，§S2 6 至 7 | Orchestration API 与 MQ；§8 至 14 为数据、安全、依赖、失败、观测、测试、波次合并章节 | 综合归纳 |
| D3，§S3 至 §S7 | Chat、Knowledge、Flow、Connector、HCI 五篇均为「1 定位与边界、2 完整能力地图、3 至 14 结构摘要」的短篇（42 至 61 行）；S7 另含 Part 渲染完整集 | 数据提取 |
| D3，§S8 3 至 10 | 模块树与依赖：同前 tioka-modules/*，biz 禁互依，依赖矩阵见历史 S8 表；§11 至 14 为失败、观测、测试、波次；附录为全局交付波次 | 直接引用 |
| D3，§S9 2.1 至 2.7 | Foundation 七类能力：身份与访问 IAM、组织与主数据同步、授权 RBAC 与数据权限、租户/空间/环境、企业 Foundation 适配器、业务系统与 IM 打通、私有化交付与合规 | 综合归纳 |
| D3，§S10 2.1 至 2.8 | Model Hub 完整能力：模型接入与目录、通用模型与决策模型分离（对标 HiRoute）、四类智能路由、切换边界与稳定性、计划生命周期、绑定到 Agent 与产品面、观测与成本、私有化与企业 | 综合归纳 |
| D3，§S11 至 §S14 | Agent 资产管理、MCP 管理（含 MCP over HTTP）、Skill 资产管理、工作流编排管理四篇，均含定位、能力地图、领域模型、组件、生命周期、管理 API、MQ、数据模型、安全、依赖、波次 | 数据提取 |
| D3，§S15 1 至 9 | Foundation 基础平台系统：结论、独立原因、定位与边界（与 Keycloak 分工）、总体架构、Java 工程化底座（现状、构件划分、工程规范、Tioka 迁移）、子系统与授权模式与微前端、与若依对标、领域模型五小节、认证与单点登录四小节 | 综合归纳 |
| D3，§S13 10.1 | Skill 执行链硬约束（ADR-014 D2）；附录为 SKILL.md 最小约定 | 直接引用 |

### D4：docs/architecture/adr/（ADR-008 至 ADR-075，共 62 份）

> 解析口径：每份登记 + 关键章节精读。ADR 通用结构为「状态、日期、覆盖工作项、来由、相关、约束、正文（背景/决策/表格/修订）」；约束行统一写「与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 ADR-030 的变更流程」。

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D4，§ADR-008 决策 | 中间件默认选型：MQ 为 RocketMQ 5.x（Topic 常量仅在 api，客户可换 Kafka Adapter）、向量库 pgvector（W3 起步，可换 Milvus Adapter）、缓存 Redis 7；状态 Accepted（W0-13），日期 2026-11-05 | 直接引用 |
| D4，§ADR-009 至 014 | 持久化 MyBatis-Plus 与 BaseDO、AgentCore 对齐包（工作盘 A / ToolGateway C / Skill / 多模态 / 凭证）、身份租户 RBAC 前置记录 | 数据提取 |
| D4，§ADR-015 页首 | 状态 Accepted（设计定案，待实现）；覆盖 FND-01 至 05、FND-07、GOV-01、CHT-01；核心决定为每个请求与每个后台任务都携带不可变 `Principal`，数据隔离在持久层自动生效；页首「术语取代注（2026-11-07）」声明标题中的「租户」及 `tenant_id`/`tenant_admin`/`Principal.tenantId` 已被 ADR-029 第 60 条与 47-GL 取代，权威隔离边界为 Workspace，平台管理员角色码为 `system_admin` | 直接引用 |
| D4，§ADR-016 至 028 | Runtime 内核（thread / turn / item）、模型网关配额审计、Knowledge 入库检索、Flow 引擎与发布、Orchestration、Connector IM 与 MCP、Chat、Agent 与 Skill 资产、Model Hub 路由、Governance、Foundation、Runtime 沙箱子 Agent、HCI 前端；ADR-016 含三段「修订」（Agent 架构调研、记忆与上下文、安全护栏） | 数据提取 |
| D4，§ADR-029 1 至 8 | 设计定案，状态 Accepted（设计定案，待实现）；覆盖全局；约束「本 ADR 优先于 ADR-015 至 028」；正文按批次分节：入库时补充定案、待确认事项答复、施工设计深化、可信放权、多智能体与运行时权限、业务本体与合同场景、实施前权威冲突裁定、M1 排期、管理面版本化与可观测、Flow 细化、M2 实施计划、M3 实施计划、Agent 架构调研落地、记忆与上下文工程、Foundation 基础平台、Knowledge 与外部知识库、文档解析与 OCR、结构树图层与证照抽取、安全护栏与结构化输出可靠性等 | 综合归纳 |
| D4，§ADR-030 1 与 24 | 防偏差机制与设计变更流程；§24 落实情况（2026-11-05） | 直接引用 |
| D4，§ADR-031 至 043 | Agent 观测评测与经验自进化（EVO-01 至 06）、可信放权 D0 至 D3（ADR-032 至 035）、业务本体与领域包（036）、结构化输出（037）、多智能体团队（038）、委派授予（039）、数据集策略与数据标签（040）、配置版本化与可观测（041）、供应商凭证与模型参数（042）、Flow 编排详细设计（043，FLW-09 至 19） | 数据提取 |
| D4，§ADR-044 至 050 | 入口意图路由与受控交接（044）、A2A 互通（045，实现在 M4 之后）、执行策略（046）、外部执行器（047，实现在 M4 之后）、上下文工程（048）、提示缓存（049）、记忆体系（050，取代 CHT-05） | 数据提取 |
| D4，§ADR-051 至 057 | Foundation 基础平台（051，详设见 S15）、组织账号权限中心（052）、Foundation 公共服务（053）、Java 工程化底座与微前端（054）、知识入库结构化切块（055）、检索问答与评测（056）、外部知识库适配（057） | 数据提取 |
| D4，§ADR-060 至 067 | 文档解析服务与 `tioka.parse/v1`（060，KNW-09 作废）、解析引擎 SPI 与外购 OCR（061）、解析质量与特殊元素安全（062）、DocAST 图层与元素处理（063）、证照票据结构化抽取（064）、护栏管线与输入安全（065，RT-14 作废）、结构化输出可靠性（066）、输出内容安全与护栏评测（067） | 数据提取 |
| D4，§ADR-068 至 075 | 评测方法规范（068）、场景 Agent 上线规程（069）、模型选型评测（070）、反馈回流与持续改进（071）、统一审计事件模型（072，GOV-09 提前）、关联上下文与 Trace 传播（073）、外部交互台账（074）、统一时间线与运行清单与证据包（075）；ADR-075 状态为 Accepted（设计定案，待实现），日期 2026-11-07，覆盖 OBS-06、OBS-08、OBS-12、FE-46、FE-47 | 数据提取 |

### D5：docs/architecture/implementation/（30 至 35 + progress + 四份签字清单）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D5，§30 1 | 一句话目标与成功定义；§2 控制面与执行面、资产挂载、模块树落地名；§3 关键路径与并行轨；§4 模块落地顺序 | 综合归纳 |
| D5，§30 5 | 默认技术冻结（W0 末 ADR 可修订，不挡 W1） | 直接引用 |
| D5，§30 8 | 每波 Exit 必须同时满足六项：门禁、相关 api 合同测试绿、黄金路径 E2E、此前已过 G 的冒烟不全红、能力地图波次标签无「永久不做」且本波任务勾选可审计、HCI 走查（W1 起）；W0 到 W1 硬门：未完成 W0-17 契约冻结，不得合并 Runtime 业务实现 PR | 直接引用 |
| D5，§31 1 头部 | 「切片任务权威：本文件 checkbox（历史 W0 至 W5 / F 任务）」；「后续推进权威：33-差距收口与后续推进计划（路径 A：R0 至 R6）」；任务格式与粒度约 0.5 至 3 人日 | 直接引用 |
| D5，§31 11 完成度词表 | 2026-11-05 复位：`[x]` 任务行等于 slice（API/页面垂直切片存在，允许 mock）；fidelity 为真实依赖路径可用；exit 对应该波 Exit 或门禁 G（含产品签字）且「全部未达」；禁止用 `scripts/accept.sh` 绿或本文件任务全勾对外宣称「W1 至 W5 Exit / G1 至 G7 已过」 | 直接引用 |
| D5，§31 W0 至 W5 | 六波各含任务、并行、回归三段；附录 A0 为 M-Gap AgentCore 对齐补强（ADR-014）、附录 A Backlog、附录 B 模块与波次速查；末段「保真加厚（F 波次 — 勾选≠企业真实依赖就绪）」 | 数据提取 |
| D5，§32 10 至 236 | 实施多轮评审记录：R1 CEO、R2 Eng、R3 Design/HCI 三轮 F-CEO/F-ENG/F-DES 条目；回写清单、签字区、实施期自动验收记录、保真加厚评审 Round-2、Round-3 设计 vs 实现差距审计与路径 A 拍板、Round-4 AgentCore 对齐补强 ADR 包落地；日期均 2026-11-05 | 数据提取 |
| D5，§33 头部 | 状态「已取代」（2026-11-05 起以 34-M0至M4推进规划与工作项为执行权威；本文保留作差距记录）；拍板 2026-11-05 选择 A 保真优先 | 直接引用 |
| D5，§33 0 | 一句话结论：31 的 checkbox 与 GateRegression 冒烟证明的是「API 垂直薄切片可串通」，不是「设计 Exit / G1 至 G7 企业就绪」 | 直接引用 |
| D5，§33 1.1 | 体量 vs 宣称表：主源 Java 约 145 文件、biz 合计约 4.8k LOC；前端约 1.1k LOC；测试约 14 个测试类；持久化 R1 已收口；默认运行 H2 加 `model.mock` 加 `mcp.mock` 加 OIDC `mock://`；MQ 为 compose 有 broker 而 Java 无生产消费；粗估各波相对 Exit 为 W0 约 70 至 80%、W1 约 35 至 45%、W2 约 30 至 40%、W3 约 25 至 35%、W4 约 15 至 25%、W5 约 10 至 20%、前端 HCI 约 25 至 35% | 直接引用 |
| D5，§33 1.2 至 1.3 | 差距四层 L1 勾选剧场、L2 工程漂移、L3 语义玩具化、L4 体验空洞；§1.3 已可用八项并定原则「加厚，不推倒重来；但勾选必须回退到诚实状态」 | 综合归纳 |
| D5，§33 3 | 执行权威复位规则 R0：勾选语义重定义、人力闸门；对内标签仅允许 slice、fidelity-partial、exit-pending；禁用对外词「工程就绪 / 真 PoC Exit / 可卖 Exit」 | 直接引用 |
| D5，§33 17 | 执行进度：R0（已完成 2026-11-05）、R1（已完成 2026-11-05，表列 `mvn -pl tioka-server -am test` 为 BUILD SUCCESS）、R2（slice 加部分 fidelity 路径，EXIT 未签）、R3（slice 为主，W2 Exit 未签）、R4（slice，G3/W3 Exit 未签）、R5（DAG slice+，G5/W4 Exit 未签）；末段「禁止开 R6」 | 直接引用 |
| D5，§34 头部 | 状态「执行权威」（2026-11-05 确认取代 33 的 R0 至 R6 计划，见 ADR-029 第 24 条）；逐项列出 M1 至 M4、F 阶段排期权威文件；第 13 行声明 2026-11-07 统一排期为唯一权威，下文旧周次与之冲突时以该节为准 | 直接引用 |
| D5，§34 统一排期 1 | 按推荐方案 B 编排：M3 W24 至 W57、M4 W58 至 W79、F W80 至 W89；总工期由约 21.6 个月缩到约 20.5 个月；M1 代码已由 PR #24 合入 main，OBS-01、OBS-02 与 UTC 时区由 PR #27 合入，M1 只剩 G1 判定 | 直接引用 |
| D5，§34 2 当前进度 | M0 已合入（PR #8、#10 至 #12）并逐行列 M0-L1 至 L3 遗留；M1 全部 28 个后端项与 9 个前端项由 PR #24 合入；G1 待判定（夜间真实模型连跑 3 天、演示走查清单 `ops/m1-closure-walkthrough.md` 待人工勾选） | 直接引用 |
| D5，§34 11 本次更正的不一致 | 九条：分阶段计划与 00 总纲旧周次、M3-00 停在 W21 至 W53、RT-10 依赖顺序、里程碑列仍写 M3、缓冲已用完、M2-00 缺 OBS 项与迁移号、M4 从未按人力核算、G3 新增 4 个用例未进表、法务投入周次旧计划 | 数据提取 |
| D5，§34 分阶段推进计划 | 六阶段：M0 地基加固（G0）、M1 安全可用的单内核（G1）、M2 人机协作闭环（G2）、M3 构建者工具链与合同场景（G3）、M4 企业完备（G4）、F 阶段 Foundation（GF）；各阶段附闸门用例描述与关键依赖；末段声明「与仓库现有 R0 至 R6 计划的关系：33 中 R2 至 R5 整体后移到 M3；建议把本文定为新的权威计划，旧文档标注为已取代」 | 综合归纳 |
| D5，§34 摘要 | 逐次记录工期修订：14 个月、15.5 个月、15.9 个月、到 G4 约 18.2 个月、到 F 结束约 20.5 个月；四条原则：地基先于功能、一个内核一套治理、完成度以自动化验收为准、假实现先下线 | 数据提取 |
| D5，§34 十层目标拆解 | 按 ENG、FND、GOV、RT、MH、ORC、CON、CHT、FLW、KNW、HCI、TRU、MAS、DAT、DOC、SCH、ONT、LSE、BIZ、BAS、PRS、GRD、EVO、OBS、FE 等前缀逐节登记工作项，并在各节注明来自哪一批 ADR 与第几条 | 数据提取 |
| D5，§34 风险与待确认事项 | 主要风险表十五行（M0 改动面、Runtime 协议替换、非 mock 验收、对标项目仅公开原型、可信放权依赖业务系统、M2 满载、人力核算、FLW-09、MCP 破坏性修订、组装顺序与提示缓存、解析错误带引文、结构化输出例外、提示注入、记忆投毒、Java 17 无虚拟线程）；确认结果六项含「本文取代 33 号文档的 R0 至 R6 计划」「不升级 Java 21」「多租户不是硬需求」 | 数据提取 |
| D5，§35 1 结论 | 规划层面已整合好但不能直接派给 Cursor 执行：M3 后段第 67 至 75 条并入的约 60 人天只有 ADR 没有施工对照；M4-01 至 M4-06 施工设计缺；前端页面规格缺；M4 不能直接执行；F 阶段设计最完整但缺任务包 | 直接引用 |
| D5，§35 4 文档不一致 | 四条：M4-00 补齐时点 W40 与 W50 冲突、34 业务本体小节 KNW-09 与 KNW 表作废冲突、S15 第 19.1 节 F 阶段 W70 至 W80 与 F-00 冲突、M3-00 末尾三节负责人与第 5.4 节不同 | 数据提取 |
| D5，§35 6 待负责人决策或确认 | D1 至 D12 共十二项：G1 判定、Q8 规格 v2.3 十组样本租约脱敏入库（已卡住 DOC-01 至 SCH-03 合同主链起点）、M3 第二三档放行开关、Q7 真实模型端点、Q4/R5 法务投入、Q5 预发布环境、Q3 目标 CPU 架构、Q10 飞书测试租户与 SMTP、M4 R3 离线目标环境、M4 R4 本地模型清单、F 阶段 R6/R7、G2 时是否按实际速度重排计划周 | 数据提取 |
| D5，§progress 测试汇总 | 生成时间 2026-11-08T11:23:17.952Z；数据来源为仓库内 Surefire 报告与 34 号计划工作项表；Surefire 报告文件 85、用例总数 330、失败 0、错误 0、跳过 1、Gate 套件通过 1、Gate 套件失败 0 | 直接引用 |
| D5，§progress 34 号计划工作项 | 共解析 292 个工作项 ID（完成状态以闸门/CI 为准，本表仅作索引）；逐条登记 ENG-01 至 MEM-01 等约 200 行，末尾标注「另有 92 项省略」 | 数据提取 |
| D5，§progress Gate 相关套件 | 仅一行：`com.tioka.server.gate.support.M3GateStubsTest` 五用例、零失败、零错误、零跳过 | 直接引用 |
| D5，§w0-contract-freeze 冻结内容 | W0-17 契约冻结检查点，状态 FROZEN v0；四项冻结：Part Schema v0 与 PartType 十八类、各域 `*MqTopics` 常量类、Foundation 五个 SPI、空 API 方法清单；变更纪律为破坏性变更须新版本字段或并行 schema，未通过冻结点不得合并 Runtime 业务实现 PR | 直接引用 |
| D5，§w1-poc-gate-signoff 状态分级 | SLICE：SIGNED（工程自动验收）；FIDELITY：ENGINEERING READY（2026-11-05）；EXIT（商业 PoC Gate）：未签；说明「工程就绪不等于商业 Gate 已过，不得在 EXIT 未签时对外宣称 PoC Gate 通过」 | 直接引用 |
| D5，§m-gap-signoff 验收项 | G-FS、G-SK、G-UP、G-TG、G-SEC 五项均 PASS；工程签字为 SLICE SIGNED，产品 Exit 标注「可选后续人工走查」且日期为破折号 | 直接引用 |
| D5，§depth-p0-checklist 门禁 | 禁用对外词三词；CH-01 五勾全为 `[x]`；门禁七项全为 `[x]`，含 fidelity 无 key 失败、Grant 越权、已发布 Version 拒改、Gateway 禁空旁路、幂等/cancel/同轮冻结、Session 钉版本、`application-fidelity.yml` 存在且 mock 为 false；页首注明「完成 P0 不等于产品 Exit 人签」 | 直接引用 |

### D6：docs/architecture/construction/（00 总纲 + F/FE/M0 至 M4，共 36 份）

> 解析口径：每份登记 + 关键章节精读。按里程碑分组登记标题与归属，00 总纲与 34 号计划交叉的更正项整节精读。

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D6，§00 1 至 5 | 文档地图、全局代码约定（2.1 包与分层、2.2 标识时间 JSON、2.3 错误与响应、2.4 事务与并发、2.5 配置、2.6 测试分层、2.7 分支 PR 与完成定义）、人员分工、周计划与关键路径、估算汇总（人天） | 综合归纳 |
| D6，§00 6 | 本次深化发现并更正的设计问题十一条：ADR-028 写 React 而实际为 Vue 3（第 26 条）、34 与 ADR-028 的 HCI 编号不一致（第 27 条）、sa-token 与 Spring Security（第 28 条）、H2 与部分索引/TIMESTAMPTZ 不兼容故引入 Flyway 新增 ENG-11（第 29 条）、M0 双实例与 RT-06 排期（第 30 条）、`auth_user.id` BIGINT 与字符串引用（第 31 条）、OIDC PKCE 前后端分工（第 32 条）、V101 删列经 V104 回填再 V105 删除（第 33 条）、事件 ID 规则（第 34 条）、编排旧表删库重建（第 35 条）、ADR-029 第 19/22 条加 `tenant_id` 与护栏「禁止 tenantId」冲突故废止加列要求（第 60 条） | 数据提取 |
| D6，§M0 组 | M0-01 工程基线、M0-02 身份与空间、M0-03 Runtime 内核，共三份；对应 34 号计划 M0 地基加固 | 索引登记 |
| D6，§M1 组 | M1-00 实施计划（安全可用的单内核）、M1-01 权限与身份联邦、M1-02 Runtime 异步与审批、M1-03 模型网关与配额、M1-04 工具网关与其他、M1-05 Agent 资产与 G1 闸门基座，共六份 | 索引登记 |
| D6，§M2 组 | M2-00 实施计划（人机协作闭环）、M2-01 消息与 Outbox、M2-02 编排、M2-03 飞书入站与卡片、M2-04 审批台与轨迹、M2-05 预演与变更单、M2-06 并行开发任务包，共七份 | 索引登记 |
| D6，§M3 组 | M3-00 实施计划（构建者工具链与合同场景）、M3-01 子 Agent 与委派授予、M3-02 数据集策略与数据标签、M3-03 业务本体与文档层、M3-04 租赁合同风险审查、M3-05 策略引擎与可信放权、M3-06 平台补齐与 G3 闸门基座、M3-07 并行开发任务包，共八份 | 索引登记 |
| D6，§M4 组 | M4-00 实施计划（企业完备）、M4-01 评测与上线、M4-02 可信放权 M4 部分、M4-03 文档解析服务、M4-04 护栏管线、M4-05 多智能体路由与记忆、M4-06 企业交付与审计、M4-07 并行开发任务包，共八份 | 索引登记 |
| D6，§F 与 FE 组 | F-00 F 阶段实施计划、F-01 F 阶段并行开发任务包、FE-00 前端信息架构与页面规格（M3、M4），共三份 | 索引登记 |

### D7：docs/architecture/design-notes/（01 至 07 + README）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D7，§README 1 至 9 | 设计材料归档 2026-11-05，原样转 Markdown 入仓；「这些文件是背景和历史，不是执行依据」；实现以 ADR、34 号计划、施工设计三处为准，冲突时以它们为准；第 8 行明确「33 号计划已被它取代」 | 直接引用 |
| D7，§README 23 | 排期以 ADR-029 第 59 条为准：M3 为 W19 至 W40、M4 为 W41 至 W52；03、04 里的 W19 至 W30 / W31 至 W42 是旧排期 | 直接引用 |
| D7，§README 24 | 05 里的「授权书（Mandate）」在 ADR 里定名为 Charter（ADR-029 第 36 条） | 直接引用 |
| D7，§01 归档说明 | 2026-11-05 归档；来源 Claude Docs 外部链接；状态为背景，描述 main 分支 fb27c4e 当时代码现状；P0/P1 建议已被 ADR-015 至 031 与 34 号计划吸收；按原文转录，内容未随后续定案更新 | 直接引用 |
| D7，§01 结论摘要 | 「设计文档覆盖十层，代码只撑起四层的演示骨架」：约 1.17 万行 Java 主代码加 3 千行前端，对应约 9 千行设计文档；Runtime、Model Hub、Knowledge、Flow 有能跑的切片，Foundation 与 Governance 基本不存在，Orchestration 只有数据表没有执行引擎；列五项更底层问题（全站无鉴权、Runtime 非 Agent 循环且不能水平扩展、Model Hub 同轮冻结实为整会话冻结、编排层只有表没有引擎、工程信号不可信） | 直接引用 |
| D7，§01 十层架构逐层对照 | 十层 D0 至 D3 深度表：Foundation 与 Governance 为 D0，Orchestration 为 D1-，Connector/Chat/HCI/Runtime/Model Hub 为 D1，Flow 与 Knowledge 为 D1+；逐层列已有与关键缺口代码证据 | 数据提取 |
| D7，§01 架构与横切问题 | 七项：安全模型缺位、单机内存状态、Runtime 同步请求模型、没有异步骨干（S0 规定生命周期走 MQ 但实际只有 `*MqTopics` 常量类且生产消费为 0）、事务边界几乎不存在（全仓只有 1 处事务注解）、模块边界有渗漏（agent-api 与 mcp-api 互相依赖，文档 module-dependencies 自己也画了这个环；tioka-server 的 OpsFacadeController 与 AssetCatalogController 直接 import 各域 application 与 infrastructure 实现类）、分层原则未执行（Runtime 经 S10 取模是 S0 硬边界，但 Flow 与 Knowledge 直连模型） | 综合归纳 |
| D7，§01 代码质量、测试与过程 | 测试：main 上 `mvn test` 构建失败（OrchAttentionTest 依赖执行顺序）、316 个主源文件对应 38 个测试类、Chat/Connector/Skill/Infra 为零、CI 只跑 Maven 不构建前端；假实现对外暴露为 API 六项（GraphRagService、CodeInterpreterService、AutomationJobService、ChatDensityService.fork、SubAgentPolicy、DifficultyRouter）；编码层面五项；过程四项含「仓库只有 1 个 commit」「docs/architecture 与 feishu-docs/Tioka 是两份手工同步的镜像，已经出现行数不一致（如 40-BA 181 行 vs 221 行）」「33 号文档 §1.1 的体量数字已过时，而 §17 又把 R1 至 R5 标成了已推进，同一天内反复修正完成度口径」 | 直接引用 |
| D7，§01 建议优先级 | P0 地基五项、P1 把主链路做真五项、P2 功能密度四项；结论为把 33 号计划里的 R2 至 R5 整体后移，先做 P0 地基 | 综合归纳 |
| D7，§02 至 07 | 02 预期目标与对标调研（84 行）；03 推进规划与详细设计 A 至 E（721 行）；04 推进规划与详细设计 F 至 N（689 行）；05 差异化能力方向数字员工全生命周期（133 行）；06 商业租赁合同本体与风险审查设计（434 行）；07 多智能体自主协作与权限管控设计（389 行） | 索引登记 |
| D7，§README 11 至 19 | 逐份登记表：01 差距分析后续落地为 P0/P1 进入 ADR-015 至 031 与 34 号计划；02 里程碑由 M0 至 M4 与 G0 至 G4 取代；03/04 为历史；05 选定 D0 至 D3（ADR-032 至 035、TRU-01 至 15、M2-05）；06 落地 ADR-036、037、M3-03、M3-04；07 落地 ADR-038 至 040、M3-01、M3-02 | 数据提取 |

### D8：docs/architecture/research/（01 至 09 + README）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D8，§README 1 | 「调研报告不是执行依据」：其中建议经负责人决定后，按 ADR-030 写进 ADR、ADR-029 定案和 34 号计划才生效 | 直接引用 |
| D8，§README 表 | 九份调研逐份登记日期、主题、状态与落地去向：01 Agent 与多 Agent 架构（2026-11-06，落地 ADR-044 至 047 与第 67 条）；02 执行策略与执行器插件（落地同上）；03 记忆与上下文工程（落地 ADR-048 至 050 与第 68 条）；04 Knowledge 与 RAG 及外部知识库（落地 ADR-055 至 057 与第 70 条）；05 文档解析与 OCR（落地 ADR-060 至 062 与第 71 条）；06 文档结构树图层与证照抽取补充（2026-11-07，落地 ADR-063、064 与第 72 条） | 数据提取 |
| D8，§01 至 09 | 另含 07 安全护栏与结构化输出可靠性、08 评测与持续改进、09 审计与可观测缺口分析；后三份在 README 表下半部登记，落地去向分别指向 ADR-065 至 067、ADR-068 至 071、ADR-072 至 075 | 索引登记 |

### D9：docs/architecture/diagrams/（30 张 drawio + 索引 + README）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D9，§README 1 至 4 | 可编辑源为本目录 drawio；文档内嵌同义 Mermaid 便于飞书 push 阅读；视觉体系 v2 定义控制面与平台为圆角矩形加蓝渐变加阴影、执行与产品为圆角矩形加绿渐变 | 直接引用 |
| D9，§DIAGRAM-INDEX 表 | 逐张登记「draw.io 名 / 覆盖文档 / 类型」：doc-hierarchy 结构、ba-capability-map 业务能力、ba-poc-sequence 场景、ta-c4-context C4、ta-layers 分层、ta-runtime-path 运行时、da-concept-er 概念模型、da-data-flow 数据流，其余覆盖 S0 至 S14、FE、EA、IMPLEMENTATION 各域 | 数据提取 |
| D9，§30 张 drawio | 按域分组：BA 三张、TA 三张、DA 两张、EA 一张、FE 三张、S0 两张、S1 至 S14 各一张、impl-waves 与 mgmt-api-mq 两张 | 索引登记 |

### D10：docs/architecture/ops/ + module-dependencies.md + feishu-doc-sync.md

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D10，§ops 清单 | 十份：BizAdapter 集成指南、HA 与备份、doc-single-source、feishu-im-续办、m1-closure-walkthrough、mcp-mock-server、oidc-keycloak、timezone-utc、指标清单、私有化安装手册 | 索引登记 |
| D10，§module-dependencies 1 | 仓库模块树列十三个业务模块（system、infra、runtime、orchestration、chat、knowledge、flow、connector、model、agent、mcp、skill），根目录另有 tioka-dependencies、tioka-framework、tioka-server*、tioka-ui；更新 2026-11-05 | 直接引用 |
| D10，§module-dependencies 2 | 编译期依赖图（只允许指向 api）含 AG 指向 MCP 与 MCP 指向 AG 两条方向相反的边，构成 agent-api 与 mcp-api 之间的环；FL 到 KN 为可选检索 DTO 虚线 | 数据提取 |
| D10，§module-dependencies 3 | biz 实现依赖表十三行；其中 mcp-biz 行写「system-api, agent-api（Grant 快照解析）, framework」；44-EA §3 摘要表 mcp 与 skill 行仅写 system-api | 直接引用 |
| D10，§module-dependencies 4 | 禁止项五条：任何 biz 依赖其他 biz；runtime-biz 直接依赖 connector；chat-biz 与 flow-biz 互相依赖；在 biz 内硬编码 MQ Topic 字符串；Agent 散落主模型字段绕过 model-api RoutingPlan | 直接引用 |
| D10，§module-dependencies 5 至 6 | 同步与异步方式表；建议拆分部署组合六个进程 | 综合归纳 |
| D10，§feishu-doc-sync 1 至 2 | 目标 PUSH/PULL/TOOL 三方向；组件树 connector-biz 下 feishu 六类 Client 与 DocSyncService；knowledge-biz 的 KnowledgeSyncApi 委托 FeishuDocSyncApi 不直连飞书 SDK | 综合归纳 |
| D10，§feishu-doc-sync 3 至 8 | 数据模型 FeishuBinding、DocSyncMapping、SyncJob（幂等键为 mappingId 加 contentHash 加 direction）；PUSH 六步、PULL 五步、与 HCI/Agent、安全四条、配置本仓库架构文档到飞书 | 综合归纳 |
| D10，§feishu-doc-sync 9 | ENG-07 仓库文档单源：权威为 docs/architecture；本地飞书镜像由 `scripts/publish-feishu-docs.mjs` 生成，默认输出 `feishu-docs/Tioka/_generated/`，页首「自动生成，勿改」；完成度由 `scripts/gen-progress.mjs` 生成 progress.md | 直接引用 |

### D11：docs/contracts/ + docs/superpowers/ + docs/archive/

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D11，§contracts/README 1 | 本目录与各模块 api 一起构成「契约」：实现 PR 只能实现，不能改契约；修改契约必须同时修改对应 ADR 并由 CODEOWNERS 审批（ADR-030） | 直接引用 |
| D11，§contracts/README M0 契约清单 | 八行：Principal/PrincipalHolder/ResourceGuard/AccessLevel/权限注解、错误码与 HTTP 状态映射、RuntimeApi 与 TurnStatus 状态转换表与 ItemType 与 RuntimeEventType 与审批枚举、Runtime SSE 事件 Schema、ModelGateway 与 ModelCallContext 与 ModelPurpose、AuditEvent 与 AuditApi 与 Audited 注解、跨域 MQ 事件信封、数据库迁移 V100 至 V105 | 数据提取 |
| D11，§contracts/README M1/M2 契约清单 | 十一行：权限点目录 Permissions、五个新错误码、审批策略 SPI 与工具风险与审批视图与续跑处理器、TurnAwaiter 与 TrajectoryApi、EventPublisher 与 DomainEvent、OrchApi 与 Receipt、orch-receipt-v1、UserTokenVault、V110 至 V119 与 V130 至 V143、闸门用例骨架 G0 至 G2 | 数据提取 |
| D11，§contracts/README 迁移脚本 | 「仓库目前用 `spring.sql.init` 加载整份 schema，尚未引入 Flyway；ENG-11（M0-01）引入 Flyway 时把 `schema-pg.sql` 作为 `V1__baseline.sql`，本目录脚本原样移入 `tioka-server/src/main/resources/db/migration`，届时删除本目录副本」；另注 V104 为 Java 迁移、V119 使用 Flyway 占位符、`V103` 至 `V106` 等删除表或列的迁移随工作项 PR 一起移入 | 直接引用 |
| D11，§contracts/README 契约测试 | TurnStatusContractTest、RuntimeEventSchemaContractTest；M1 起按 ADR-030 第 4 条补充租约失效、审批、Flow 调度的表驱动契约测试 | 直接引用 |
| D11，§contracts/db 与 events | db 目录 45 份 SQL（V100 至 V216，含 drop 与 rename 脚本）；events 目录 39 份 JSON 事件 Schema（mq-envelope-v1、tioka.runtime.turn_finished.v1、tioka.orch.run_created.v1、tioka.trust.action_undone.v1 等）；另有 agent/agent-spec-v1.json、biz/lease-biz.openapi.yaml、flow/flow-dsl-v1.schema.json | 索引登记 |
| D11，§superpowers/plans | 五份计划：depth-p0-poc-fidelity、persistence-guardrails-mybatis-plus、tioka-global-implementation、m0-m1-closure-path-a、scopeline-deny-by-default | 索引登记 |
| D11，§superpowers/specs | 十六份规格：agentcore-alignment-gap-design、implementation-depth-gap-inventory、persistence-guardrails-mybatis-plus-design、tioka-agent-platform-architecture，以及 2026-11-07 的 runtime/chat/model/orch-flow/knowledge-connector/agent-mcp-skill/m0-m1-closure/coding-conventions 等名词对齐与编码规约设计 | 索引登记 |
| D11，§archive/README | 本目录存放从手工镜像与 docs/architecture 重复迁出的材料，不作为执行依据；权威源为 docs/architecture；飞书只读镜像由 publish 脚本输出到 `feishu-docs/Tioka/_generated/`；完成度由 gen-progress 脚本生成 | 直接引用 |

### D12：feishu-docs/Tioka/（手工同步镜像）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D12，§README 首段 | 「飞书知识库是发布面；以本目录 Markdown 为源，改完后 push」；目录层级与飞书同构：00-项目主页、01-管理文档、02-企业架构、03-能力域详设、04-实施交付、diagrams、ops | 直接引用 |
| D12，§01-管理文档 | 五份：01-需求文档、02-技术方案总览、03-接口与数据总表、04-决策记录、05-发布与运维；另有 adr/ADR-014-agentcore-alignment-package.md 一份 | 索引登记 |
| D12，§02-企业架构 | 八份：40-BA 至 46 加 47-图形多轮评审记录；无 47-GL-统一名词释义、无 48-CS-编码规约摘要 | 数据提取 |
| D12，§03-能力域详设 | 十六份 S 文档，按产品与交互、总览与基座、执行与编排、模型与资产控制面、连接器五组重排并加数字前缀（10-S0 至 24-S14）；文件名为 `13-S3-Chat对话域.md` 等形式 | 数据提取 |
| D12，§04-实施交付 与 diagrams 与 ops | 实施交付含 30 至 33、depth-p0-checklist、feishu-im-续办、m-gap-signoff、w1-poc-gate-signoff（无 34、35、progress、w0-contract-freeze）；diagrams 含三十张 drawio、三十张 mmd 中间文件、三十张 png 导出与索引；ops 含 feishu-im-续办、mcp-mock-server、oidc-keycloak 三份 | 索引登记 |
| D12，§行数复核 | 40-BA 本地 181 行、镜像 221 行；41-TA 178 对 178；42-DA 166 对 166；43-AA 151 对 176；44-EA 196 对 219；45-FE 202 对 252；46 139 对 146；镜像 40-BA 含 `DIAGRAMS:BEGIN` 标记两处而本地为零处；镜像无「自动生成，勿改」页首；`feishu-docs/Tioka/_generated/` 目录不存在 | 数据提取 |

### D13：构建与模块树（pom.xml 与各模块 pom）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D13，§1 根 POM | artifactId 为 tioka，version 为 `${revision}`；modules 五模块：tioka-dependencies、tioka-framework、tioka-modules、tioka-testkit、tioka-server；java.version 为 17；spring-boot.version 为 3.2.12；maven-surefire-plugin 3.5.2、maven-failsafe-plugin 3.5.2、maven-enforcer-plugin 3.5.0、flatten-maven-plugin 1.6.0 | 数据提取 |
| D13，§2 tioka-modules/pom.xml | 声明十六个子模块：system、infra、runtime、orchestration、chat、knowledge、flow、connector、model、agent、mcp、skill、ontology、governance、eval（十五个业务域）与聚合 POM 自身 | 直接引用 |
| D13，§3 双子模块 | 每个业务模块均拆为 `tioka-module-xxx-api` 与 `tioka-module-xxx-biz` 两个 Maven 子模块，共三十二份 api/biz pom；与 44-EA §1 及 module-dependencies §1 相符，但业务域数量由十三增至十五 | 数据提取 |
| D13，§4 BOM | tioka-dependencies 为版本锁定 BOM；根 POM 通过 dependencyManagement 引入；`.flattened-pom.xml` 与各模块 `.flattened-pom.xml` 为 flatten 插件产物 | 索引登记 |

### D14：tioka-framework/（框架层代码证据）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D14，§1 规模 | 53 个 main 源、10 个 test 源，合计 63 个 java；与上游转交口径「63 个 Java 主源」数值吻合但口径不同（实测 63 为 main 加 test 全量） | 数据提取 |
| D14，§2 包结构 | 十一个包：context、error、id、jackson、json、label、messaging、persistence、security、tenant、trace；security 对应 ADR-015 的 Principal 与权限注解，error 对应 ErrorCode 与 HTTP 状态映射，messaging 对应 EventPublisher 与 DomainEvent 与 IdempotentConsumer | 直接引用 |
| D14，§3 关键类定位 | `tioka-framework/src/main/java/com/tioka/framework/messaging/EventPublisher.java`、`DomainEvent.java`、`IdempotentConsumer.java`（D14，路径#messaging）；`com/tioka/framework/error/ErrorCode.java`（D14，路径#error）；`com/tioka/framework/tenant/TenantContext.java` 与 `context/RequestContext.java` 为 47-GL §4 登记的技术债（D14，路径#tenant） | 数据提取 |

### D15：tioka-modules/（业务域代码证据）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D15，§1 规模 | 818 个 main 源、79 个 test 源，合计 897 个 java；十五个业务域逐域计数（含 api 与 biz 拆分）：agent 53、chat 16、connector 68、eval 8、flow 55、governance 45、infra 45、knowledge 44、mcp 22、model 72、ontology 48、orchestration 87、runtime 173、skill 12、system 149 | 数据提取 |
| D15，§2 包结构 | runtime 域包最全：agui、api（approval、context、event、grant、kernel、mq、pipeline、spi）、application（gateway、pipeline、tool）、config、infrastructure（mapper、persistence）、kernel/api；对应 S1 的 API 契约完整面与 ADR-016 的 thread/turn/item | 数据提取 |
| D15，§3 关键 api 接口 | `com/tioka/runtime/api/kernel/RuntimeApi.java` 与 `TrajectoryApi.java`（D15，路径#runtime-api）；`com/tioka/model/api/ModelRouterApi.java`（D15，路径#model-api）；`com/tioka/orchestration/api/engine/OrchApi.java` 与根包 `com/tioka/orchestration/api/OrchApi.java` 并存（D15，路径#orchestration-api，对应 47-GL §4「根包过渡」遗留项）；`com/tioka/agent/api/AgentSnapshotApi.java`、`com/tioka/knowledge/api/KnowledgeQueryApi.java`、`com/tioka/flow/api/FlowInvokeApi.java`、`com/tioka/connector/api/FeishuDocSyncApi.java`、`com/tioka/system/api/audit/AuditApi.java`、`com/tioka/ontology/api/doc/DocumentApi.java`、`com/tioka/governance/api/config/ConfigVersionApi.java`、`com/tioka/eval/api/dataset/EvalDatasetApi.java` | 数据提取 |
| D15，§4 契约与实现分层 | 每域 api 子模块放接口、DTO、MQ 常量、SPI；biz 子模块放实现；与 44-EA §1 与 w0-contract-freeze 的空 API 方法清单对齐 | 综合归纳 |

### D16：tioka-server/（启动、迁移与配置）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D16，§1 java 规模 | 11 个 main java、168 个 test java，合计 179；main 仅六个类加五份 Java 迁移：`TiokaServerApplication`、`AssetCatalogController`、`TiokaMcpOutboundServer`、`OpsFacadeController`、`RestExceptionAdvice`，以及 `db/migration/V104__BackfillRuntimeItems`、`V124__BackfillAgentSpec`、`V126__DropChatMessage`、`V145__CredentialReencrypt`、`AgentSpecBackfill`、`ChatMessageDropGuard` | 数据提取 |
| D16，§2 迁移脚本 | `src/main/resources/db/migration/` 下 41 份 SQL，含 `V1__baseline.sql` 与 V100 至 V216；`docs/contracts/db/` 另有 45 份 SQL，两处副本并存；`V104__BackfillRuntimeItems`、`V124__BackfillAgentSpec`、`V126__DropChatMessage`、`V145__CredentialReencrypt` 为 Java 迁移，实现 `BaseJavaMigration` | 直接引用 |
| D16，§3 配置 | `application.yml` 含 `flyway` 配置段与 `model.mock: true`、`mcp.mock: true`（注释写 local/slice 默认 mock，保真请叠加 profile=fidelity）；另有 application-local.yml、application-prod.yml、application-fidelity.yml、logback-spring.xml；`application-local.yml` 亦含 `flyway` 段 | 直接引用 |
| D16，§4 测试资源 | `src/test/resources` 含大量 yaml 场景夹具（failover-mid-stream、backoff-retry、async-defer、mcp-three-steps、write-needs-approval 等，各两份）与 keycloak realm 导入文件；gate 测试位于 `com/tioka/server/gate/` | 索引登记 |

### D17：tioka-ui/（前端代码证据）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D17，§1 技术栈 | package.json：vue 3.5.12、pinia 4.1.0、element-plus 2.14.7、vue-router 4.4.5、typescript 6.0.3、vite 5.4.10、vitest 3.2.7、vue-tsc 3.3.14；scripts 含 dev、build（vue-tsc 加 vite build）、test（vitest run）、test:ch01、typecheck、test:fixtures | 数据提取 |
| D17，§2 文件构成 | src 下 64 个 ts、63 个 vue、22 个 json、3 个 snap、1 个 md、1 个 gitkeep；未见 tsx 文件；与上游转交口径「64 个 ts/tsx」数值吻合在 ts 一项 | 数据提取 |
| D17，§3 目录结构 | api（http、stream、approval、orch、trajectory、ops、im、chatAsync、connect）、chat、items（ItemRenderer.vue 与 13 种 Item 组件、itemRegistry、dataItemRegistry、groupItems、validateArgs）、components/state（PageEmpty/Error/Forbidden/Loading/LongTask/StaleVersion）、views（Chat、FlowStudio、Knowledge、Mcp、Models、Agents、Skills、Observability、Shell、Login 等，另有 admin 九页、approval、orch/rules、trajectory、connect、im、ops、m3 占位页）、router、stores（auth、thread） | 索引登记 |
| D17，§4 测试信号 | 前端含 20 余份 spec.ts 与快照（ItemRenderer.spec.ts、Router.spec.ts、ApprovalDesk.spec.ts、TrajectoryView.spec.ts、chatUx.test.ts、threadState.test.ts、flattenTimeline.spec.ts 等）；depth-p0-checklist 记载 `npm run test:ch01` 五比五通过 | 数据提取 |

### D18：tioka-testkit/ + deploy/ + scripts/

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D18，§1 testkit | 仅两个 java：`src/main/java/com/tioka/testkit/FakeErp.java` 与 `src/test/java/com/tioka/testkit/FakeErpTest.java`；对应 34 号计划「FakeErp 保证闸门可测」 | 数据提取 |
| D18，§2 deploy/compose.yml | 五服务：postgres（pgvector/pgvector:pg16，5432）、redis（redis:7-alpine，6379）、keycloak（quay.io/keycloak/keycloak:24.0，8180，导入 tioka-realm.json）、rocketmq-namesrv（apache/rocketmq:5.3.1，9876）、rocketmq-broker（10911）；另有 deploy/Dockerfile 与 init-pgvector.sql | 直接引用 |
| D18，§3 scripts | 七份 sh（accept-fidelity、accept-poc-exit、accept、check-connector-coverage、check-test-order、smoke-llm-live、smoke-mock-off）、六份 mjs（embed-diagrams、export-diagram-pngs、gen-drawio、gen-item-fixtures、gen-progress、publish-feishu-docs）、一份 py（migrate-mapper-annotations-to-xml） | 索引登记 |

### D19：测试与完成度信号（surefire-reports + progress.md）

| 章节 | 内容摘要 | 引用方式 |
| --- | --- | --- |
| D19，§1 报告规模 | 全仓 85 份 `target/surefire-reports/*.xml` 与 85 份同名 txt；另有 gate 测试 `com.tioka.server.gate.support.M3GateStubsTest` | 数据提取 |
| D19，§2 聚合值 | 逐份读取 XML 头属性聚合：tests 330、failures 0、errors 0、skipped 1；与 progress.md「用例总数 330、失败 0、错误 0、跳过 1」完全一致 | 数据提取 |
| D19，§3 工作项登记 | progress.md 共解析 292 个工作项 ID，末尾标注「另有 92 项省略」；明确「完成状态以闸门/CI 为准，本表仅作索引」 | 直接引用 |
| D19，§4 生成机制 | progress.md 页首「自动生成，勿改。由 scripts/gen-progress.mjs 写入。CODEOWNERS：CI 机器人 + 架构负责人」；生成时间 2026-11-08T11:23:17.952Z | 直接引用 |
| D19，§5 提交历史 | git rev-list 计数 288 个 commit；最近提交含「重跑全量测试刷新完成度表（330 用例全绿）」「重组架构文档目录，归档旧 README 并新增 enterprise 分册」「ORC-04 回执 ask 挂起」「ORC-06 orch_timer 扫描派发」「FND-08 CredentialCipher AES-GCM 与 V145 重加密」等 | 数据提取 |
| D19，§6 推断与风险 | 由「85 报告全绿 + 292 工作项登记 + 288 commit」推断代码已从 2026-11-05 归档快照状态大幅推进；但本角色未运行 mvn test、未核对每个工作项与代码的对应关系，推断风险为「绿只代表自动化信号，不代表 Exit 或产品签字，也不代表设计一致」，须由下游按域逐项核对 | 推断（风险已标注） |

---

## 3. 冲突记录

> 不同资料对同一事实描述矛盾时，并列保留多个版本，不做裁决。版本 A 与版本 B 单元格内用带圈数字区分多版本，出处逐一对应。所有条目均等待主理人或下游架构师裁决，本摘要不判定谁对谁错。

| 编号 | 冲突主题 | 版本 A | 出处 A | 版本 B | 出处 B | 差异说明 |
| --- | --- | --- | --- | --- | --- | --- |
| X1 | 执行权威指针 | ① README 称「后续推进权威：33-差距收口与后续推进计划（路径 A：R0 至 R6）」；② 31 头部亦称「后续推进权威：33（路径 A：R0 至 R6）」 | ① D1，§24；② D5，§31 1 | ① 33 头部自述「状态：已取代（以 34 号为执行权威；本文保留作差距记录）」；② 34 头部自述「状态：执行权威（确认取代 33 的 R0 至 R6 计划）」；③ design-notes/README 写「33 号计划已被它取代」；④ enterprise/README 写「推进计划见 34」；⑤ 34 分阶段末段写「建议把本文定为新的权威计划，旧文档标注为已取代」 | ① D5，§33 头部；② D5，§34 头部；③ D7，§README 8；④ D2，§README 22；⑤ D5，§34 分阶段 | 同一仓库内两处指针指向 33、五处指向 34；33 与 34 互相确认取代关系。并列保留，交由主理人裁定校验基线采用哪一份 |
| X2 | 体量、测试与完成度信号 | ① design-notes/01 归档快照（对应 main fb27c4e，2026-11-05）：约 1.17 万行 Java 主代码、316 个主源文件对应 38 个测试类、mvn test 红、Chat/Connector/Skill/Infra 零测试、仓库只有 1 个 commit；② 33 §1.1：主源 Java 约 145 文件、biz 约 4.8k LOC、测试约 14 类、默认 H2 加 mock、MQ Java 无生产消费 | ① D7，§01 结论摘要与代码质量测试与过程；② D5，§33 1.1 | ① progress.md（2026-11-08T11:23:17.952Z）：85 份 Surefire 报告、330 用例、0 失败、0 错误、1 跳过、292 个工作项登记；② 实测 2026-11-08：883 个 main java 加 258 个 test java 合计 1141，85 份 XML 聚合 330/0/0/1，git 288 个 commit；③ 31 完成度词表：exit 全部未达，禁止宣称 W1 至 W5 Exit 或 G1 至 G7 已过 | ① D5，§progress 测试汇总；② D19，§1 至 §5；③ D5，§31 11 | 时间跨 3 天且统计口径不同（归档快照、自动生成完成度、实测文件数、完成度词表）；并列保留，不裁决哪一个代表「当前状态」 |
| X3 | 文档权威源与镜像生成机制 | ① enterprise/README：docs/architecture 为唯一权威源，勿再手工维护 feishu-docs；② feishu-doc-sync §9 与 archive/README：镜像由 publish 脚本生成到 `feishu-docs/Tioka/_generated/`，页首「自动生成，勿改」；③ progress.md 由 gen-progress 脚本生成 | ① D2，§README 1；② D10，§feishu-doc-sync 9 与 D11，§archive/README；③ D5，§progress 页首 | ① feishu-docs/Tioka/README：「飞书知识库是发布面；以本目录 Markdown 为源，改完后 push」；② design-notes/01：「docs/architecture 与 feishu-docs/Tioka 是两份手工同步的镜像，已经出现行数不一致（如 40-BA 181 行 vs 221 行）」；③ 实测：`_generated/` 目录不存在，飞书 40-BA 无「自动生成」页首且含 `DIAGRAMS:BEGIN` 标记两处（本地 40-BA 零处） | ① D12，§README 首段；② D7，§01 过程；③ D12，§行数复核 | 权威源指向相反（本地唯一权威 与 镜像为源）；声明的自动生成机制与实测目录状态不符。并列保留 |
| X4 | 双份镜像的行数与文件集 | 本地 docs/architecture/enterprise 行数：40-BA 181、41-TA 178、42-DA 166、43-AA 151、44-EA 196、45-FE 202、46 139；另有 47-GL 239 行、48-CS 100 行 | D2，§40-BA 至 §48-CS（行数复核） | 飞书镜像行数：40-BA 221、41-TA 178、42-DA 166、43-AA 176、44-EA 219、45-FE 252、46 146；另有 47-图形多轮评审记录 92 行；无 47-GL 与 48-CS | D12，§行数复核 | 七份同名文档中五份行数不同；文件集亦不同（飞书多一份 47-图形、缺 47-GL 与 48-CS）。design-notes/01 仅举 40-BA 一例，本轮已复核七份并补充文件集差异，并列保留 |
| X5 | 33 号文内部完成度口径 | §1.1 与 §1.2、§3.1：完成度定义为 slice、fidelity-partial、exit-pending；各波相对 Exit 仅 10% 至 80%；31 完成度词表亦写「exit 全部未达」；禁用对外词「工程就绪 / 真 PoC Exit / 可卖 Exit」 | D5，§33 1.1、§33 1.2、§33 3；D5，§31 11 | §17 执行进度：R0 与 R1 标「已完成 2026-11-05」，R1 表列 `mvn -pl tioka-server -am test` 为 BUILD SUCCESS，R2 至 R5 标 slice 或 fidelity-partial 且「验证 slice 绿」；design-notes/01 指出「§17 又把 R1 至 R5 标成了已推进，同一天内反复修正完成度口径」 | D5，§33 17；D7，§01 过程 | 同文 §1.1 的「未完成」与 §17 的「已完成/已推进」并存；并列保留，不裁决 R1 至 R5 真实状态 |
| X6 | 设计内部依赖环与依赖矩阵 | ① 44-EA §2 R1「biz 禁止依赖其他 biz」；§3 允许依赖矩阵写「mcp / skill 可依赖 api：system-api」；② module-dependencies §4 禁止项「任何 biz 依赖其他 biz」 | ① D2，§44-EA 2 与 §44-EA 3；② D10，§module-dependencies 4 | ① module-dependencies §2 编译期依赖图存在 AG 指向 MCP 与 MCP 指向 AG 两条反向边（构成环）；§3 表写「mcp-biz 允许依赖 system-api, agent-api（Grant 快照解析）, framework」；② design-notes/01 第 6 项「agent-api 与 mcp-api 互相依赖（文档 module-dependencies.md 自己也画了这个环）」 | ① D10，§module-dependencies 2 与 §module-dependencies 3；② D7，§01 架构与横切问题 | 同一份依赖文档内部对 mcp 到 agent 的描述不一致，且与 44-EA 摘要矩阵不一致；并列保留 |
| X7 | 契约目录自述「尚未引入 Flyway」与代码现状 | contracts/README §迁移脚本：「仓库目前用 spring.sql.init 加载整份 schema，尚未引入 Flyway；ENG-11（M0-01）引入 Flyway 时把 schema-pg.sql 作为 V1__baseline.sql，本目录脚本原样移入 db/migration，届时删除本目录副本」 | D11，§contracts/README 迁移脚本 | ① 实测 application.yml 与 application-local.yml 均含 flyway 配置段；② `tioka-server/src/main/resources/db/migration/V1__baseline.sql` 与 V100 至 V216 共 41 份 SQL 已就位；③ 四份 Java 迁移实现 BaseJavaMigration；④ `docs/contracts/db/` 仍存 45 份 SQL 副本未删除；⑤ 00-施工总纲 §6 第 4 项已把「引入 Flyway（新增 ENG-11）」列为更正（ADR-029 第 29 条） | ① D16，§3；② D16，§2；③ D16，§1；④ D11，§contracts/db；⑤ D6，§00 6 | 契约目录 README 的「尚未引入」与代码现状并存，且「届时删除本目录副本」尚未发生；并列保留，供下游核对文档新鲜度 |
| X8 | 设计模块树与代码模块树 | 44-EA §1 仓库布局与 module-dependencies §1 均列十三个业务模块（system、infra、runtime、orchestration、chat、knowledge、flow、connector、model、agent、mcp、skill），44-EA 另提「可选拆分 tioka-module-foundation」 | D2，§44-EA 1；D10，§module-dependencies 1 | 实测 tioka-modules/pom.xml 声明十五个业务子模块，多出 ontology、governance、eval 三个；三者分别有 api 与 biz 双子模块与源码（48、45、8 个 java） | D13，§2；D15，§1 | 设计模块树未覆盖三个已落地模块；并列保留，交由下游按域判定属于「漂移」还是「设计已由 ADR-025/031/036 补充但未回写模块树」 |
| X9 | Gate 与 Exit 的编号语义 | S0 §13 验收门禁：G1 执行面（工具、MCP、流式、Steer、Cancel）、G1b 模型面、G2 人机面、G3 知识面、G4 编排面、G5 编排面加 Flow、G6 治理面、G7 企业接入面 | D3，§S0 13 | ① 34 分阶段推进计划：G0（M0）、G1（M1 安全可用的单内核）、G2（M2 人机协作闭环）、G3（M3）、G4（M4）、GF（F 阶段）；② w1-poc-gate-signoff 用 SLICE / FIDELITY / EXIT 三级；③ 33 用 R0 至 R6 波次；④ 35 §1 列 G3 新增 4 个用例、G4 十七个用例、GF 十二个用例；⑤ 31 §11 禁止宣称「W1 至 W5 Exit / G1 至 G7 已过」 | ① D5，§34 分阶段；② D5，§w1-poc-gate-signoff；③ D5，§33 4；④ D5，§35 1；⑤ D5，§31 11 | 同为 G1 至 G4 标签，在 S0 与 34 指向不同能力簇与不同里程碑；另有多套完成度词表并存。并列保留，须由主理人锁定本次校验采用哪一套 Gate 定义 |
| X10 | 排期周次与总工期 | design-notes/README §几点说明：「排期以 ADR-029 第 59 条为准：M3 为 W19 至 W40，M4 为 W41 至 W52」，并声明 03/04 里的 W19 至 W30 / W31 至 W42 为旧排期；35 §4 记 S15 第 19.1 节写 F 阶段 W70 至 W80 | D7，§README 23；D5，§35 4 | ① 34 统一排期（自称 2026-11-07 重排，单一权威）：M3 W24 至 W57、M4 W58 至 W79、F W80 至 W89，到 G4 约 18.2 个月、到 F 结束约 20.5 个月；② 34 摘要逐次记录 14、15.5、15.9、18.2、20.5 个月；③ 34 §11 列九条「本次更正的不一致」；④ 35 §4 记 F-00 第 1 节声明以 F-00 为准 | ① D5，§34 统一排期 1；② D5，§34 摘要；③ D5，§34 11；④ D5，§35 4 | 多份文档保留历史周次；34 自称单一权威并列出九条更正，但未回写所有引用点。并列保留 |
| X11 | 术语权威与历史残留 | ADR-015 标题「身份、租户与权限」，正文使用 `tenant_id`、`tenantId`、`tenant_admin`、`Principal.tenantId`；00-施工总纲 §6 第 11 项记 ADR-029 第 19/22 条原要求加 `tenant_id` | D4，§ADR-015 页首；D6，§00 6 | ① ADR-015 页首「术语取代注（2026-11-07）」与 47-GL §3：权威隔离边界为 Workspace、平台管理员角色码 `system_admin`、新建 DO 与 API 禁止 `tenantId`；② 00-施工总纲第 11 项定案废止 ADR-029 第 19/22 条加列要求；③ 47-GL §4 遗留登记十三项仍列 TenantContext、RequestContextHolder 中 `tenantId`、历史 Flyway 字面量、ADR-015 标题等 | ① D4，§ADR-015 页首与 D2，§47-GL 3；② D6，§00 6；③ D2，§47-GL 4 | 术语权威与历史文档、代码残留并存；47-GL 已自我登记十三项遗留。并列保留，供下游逐项核对代码是否已按 Workspace 口径落地 |
| X12 | 前端技术栈与文件类型口径 | 00-施工总纲 §6 第 1 项记 ADR-028 原写「保留现有 React + TypeScript + Vite」，更正为 Vue 3 + Element Plus + Pinia + vue-flow（ADR-029 第 26 条）；上游转交口径写「tioka-ui/src 下 64 个 ts/tsx」 | D6，§00 6；§0 原始资料清单 | ① 实测 package.json：vue 3.5.12、pinia 4.1.0、element-plus 2.14.7、vite 5.4.10、vitest 3.2.7；② src 下 64 个 ts、63 个 vue、22 个 json、3 个 snap，未见 tsx 文件 | ① D17，§1；② D17，§2 | 设计更正方向与代码一致；但类型枚举口径需按实测修正为 ts 与 vue 为主。并列保留 |
| X13 | MQ 与异步骨干现状 | ① design-notes/01 第 4 项：「S0 规定生命周期走 MQ，实际只有 `*MqTopics` 常量类，生产消费为 0」；② 33 §1.1：「MQ：compose 有 broker；Java 无生产/消费」 | ① D7，§01 架构与横切问题；② D5，§33 1.1 | ① 实测 `tioka-framework/src/main/java/com/tioka/framework/messaging/` 存在 EventPublisher、DomainEvent、IdempotentConsumer 三个类且引用 rocketmq；② deploy/compose.yml 声明 rocketmq-namesrv 与 rocketmq-broker（apache/rocketmq:5.3.1）；③ contracts/README M1/M2 契约清单列 EventPublisher 与 DomainEvent 位于 framework/messaging；④ 34 分阶段 M2 计划写「RocketMQ + outbox」 | ① D14，§3；② D18，§2；③ D11，§contracts/README M1/M2；④ D5，§34 分阶段 | 归档快照的「0」为 2026-11-05 状态，现有 messaging 包与 compose broker 已落地；生产消费闭环是否成立需下游按域实测。本条含推断，风险：仅按类名与配置判断，未做运行时链路验证 |
| X14 | Java 代码规模计数口径 | 上游转交口径：tioka-framework 63 个 Java 主源、tioka-modules 共 897 个 Java 主源、tioka-server 179 个 Java、当前仓库约 1139 个 Java 主源 | §0 原始资料清单 | 实测 2026-11-08：main 源 883 个（framework 53、modules 818、server 11、testkit 1），test 源 258 个（10、79、168、1），合计 1141 个 java；逐模块全量 java 为 framework 63、modules 897、server 179、testkit 2 | D14，§1；D15，§1；D16，§1；D19，§5 | 上游数字与实测「全量 java（含 test）」吻合，与「main 主源」口径不符；并列保留，供下游选定统计口径后逐域对账 |

### 3.1 34 号计划工作状态（登记，不裁决）

| 项 | 登记内容 | 出处 |
| --- | --- | --- |
| 阶段进度 | M0 已合入（PR #8、#10 至 #12）；M1 全部 28 个后端项与 9 个前端项由 PR #24 合入 main（2026-11-07）；OBS-01、OBS-02 与 UTC 时区由 PR #27 合入；M1 只剩 G1 判定 | D5，§34 2 当前进度 |
| G1 判定条件 | 夜间真实模型 workflow 连跑 3 天通过、演示走查清单 `ops/m1-closure-walkthrough.md` 人工勾选 | D5，§34 2；D5，§35 6 D1 |
| 工作项登记 | progress.md 解析 292 个工作项 ID，另标注 92 项省略，且声明「完成状态以闸门/CI 为准，本表仅作索引」 | D5，§progress 34 号计划工作项 |
| 施工设计缺口 | M3 后段第 67 至 75 条约 60 人天只有 ADR 没有施工对照；M4-01 至 M4-06 施工设计缺；前端页面规格缺；M4 不能直接执行 | D5，§35 1 |
| 待负责人决策 | D1 至 D12 共十二项，其中 D2「Q8 规格 v2.3 十组样本租约脱敏入库」标注「现在已卡住」合同主链起点 | D5，§35 6 |
| 本角色备注 | 以上均为设计文档自述状态，须由下游按域以代码证据核对；本摘要不判定 34 号计划实际完成百分比 | 推断（风险：设计自述与代码现状可能不同步，须逐项核对） |

### 3.2 缺失与待外部验证点

| 编号 | 事项 | 类型 | 处理建议 |
| --- | --- | --- | --- |
| V1 | design-notes/01、02、03/04、05、06、07 的 Claude Docs 外部链接（artifact 形式的六个 URL） | 外部资料未取回 | 本轮未联网取回；若下游需要原文，须由主理人授权外部访问 |
| V2 | 真实飞书租户同步状态、`FEISHU_APP_ID` 与 `FEISHU_APP_SECRET` 配置、真实模型夜间 workflow 结果、Testcontainers 与 Keycloak 验收记录 | 仓库外运行时证据 | 不在仓库内，须由主理人向用户或运维侧取证 |
| V3 | 三十张 drawio 的图形内容与设计文档文字是否一致 | 图形源未做内容比对 | 仅登记索引（D9）；图形级比对需图形渲染与人工查看 |
| V4 | 45-FE 与 ADR-028 的前端页面规格是否已在 FE-00 落地、各 Placeholder 页面对应哪些未完成链路 | 待按页核对 | 由 G4 或前端域架构师按 D17 目录逐页核对 |
| V5 | contracts/db 的 45 份 SQL 与 tioka-server/db/migration 的 41 份 SQL 是否为同一版本、哪些已执行 | 双份副本未做逐行 diff | 由数据域架构师按版本号逐份对账 |
| V6 | 六个假实现 API（GraphRAG、代码解释器、自动化任务、fork、SubAgent、难度路由）当前是否仍存在或已按 ENG-03 下线 | 归档快照与现状未对账 | 由下游按 D7，§01 表名逐类检索代码 |
| V7 | 85 份 Surefire 报告的绿色信号是否覆盖非 mock 路径与双实例场景 | 完成度口径 | 31 完成度词表与 w1 签字页均声明不等同 Exit；须由主理人锁定 Gate 定义后再判定 |

---

## 4. 硬指标清单

| 章节 | 硬指标 | 状态 |
| --- | --- | --- |
| §1 | 每份资料有解析状态，失败/跳过注明原因 | 通过 |
| §2 | 每份文档按章节逐条摘要，每条标注了 `D编号，§章节` | 通过 |
| §3 | 冲突信息并列保留，不做裁决 | 通过 |
| §0 | 元信息完整（标题、版本、状态、日期、整理人、审核人、原始资料清单） | 通过 |
| §2 | 设计基线索引与代码现状证据索引双索引齐备 | 通过 |
| §3 | 用户指定的五条已知矛盾线索全部命中并复核 | 通过 |
| 通用 | 全文无残留占位符、无填写参考前缀、无事实缺口标记 | 通过 |

**自检明细**：

| 检查项 | 实测值 |
| --- | --- |
| 文档族数量 | 19 个（D1 至 D19）；设计侧 D1 至 D12，代码侧 D13 至 D19 |
| 已登记原始资料族覆盖 | 主理人转交清单中的 README、enterprise、systems、adr、implementation、construction、design-notes、research、diagrams、ops、contracts、superpowers、archive、feishu-docs、pom 与模块树、framework、modules、server、ui、testkit、deploy、scripts、surefire 与 progress 全覆盖 |
| 解析状态为「跳过」并注明原因的族 | 1 个（D9 图形源）；另有 D4、D6、D11、D15 标注「每份登记 + 关键章节精读」或「索引登记」并逐条注明原因 |
| 解析失败资料 | 0 个（本轮无解析失败） |
| `D编号，§章节` 出处标注条数 | §2 内 `D编号，` 形式出处 224 处（其中含 `§` 加数字的 98 处）；全文共 303 处（含 `§` 加数字的 157 处），覆盖 §2 全部十九个族与 §2.0 双索引导航 |
| 冲突记录条数 | 14 条（X1 至 X14），每条至少两个版本且各自带出处 |
| 已知矛盾线索命中 | X1 命中线索 1；X2 与 X5 命中线索 2 与 4；X3 与 X4 命中线索 3 并复核七份文档；X6 至 X14 为线索 5 的补充发现 |
| 待外部验证点 | 7 条（V1 至 V7） |
| 定稿占位符扫描 | 尖括号包裹字符串 0、字面量日期占位 0、字面量待定标记 0、填写参考前缀 0、事实缺口标记 0 |
| 日期 | 全文统一使用真实日期 2026-11-08 |
| 规模 | 本文件约 400 至 700 行区间内，以可追溯索引加关键约束摘要为主，未全文转录长文档 |

**本角色边界声明**：本摘要只做输入整理与索引建立，未做代码与设计的一致性裁决、未下架构或技术选型结论、未进入行业调研、未修改仓库任何源码或既有设计文档、未生成 material_digest.md 之外的交付文档。全部冲突按 §3 并列保留，交由主理人与下游架构师裁决。

---

## 附录 A：生成流程

### 流程总览

| 步骤 | 动作 | 落入章节 |
| --- | --- | --- |
| Step0 | 读取模板与全部原始资料路径、来源说明、整理目标 | §0 |
| Step1 | 盘点资料清单，按文档族编号并标注解析状态 | §1 |
| Step2 | 逐份打开资料，按自身章节结构逐条摘要，建立设计基线与代码证据双索引 | §2 |
| Step3 | 交叉比对不同资料，发现并记录矛盾，并列保留不裁决 | §3 |
| Step4 | 逐项核验硬指标、引用定位与占位符 | §4 |

```mermaid
flowchart LR
    S0[读取模板与资料] --- S1[盘点资料清单]
    S1 --- S2[逐份精读逐章节摘要]
    S2 --- S3[交叉比对记录冲突]
    S3 --- S4[硬指标自检]
```

**本轮实际执行顺序**：先读模板与 validator 脚本确认校验矩阵与占位符正则，再按文档族分组读取原始资料（全量精读小文档、按标题结构精读中文档、按清单登记大族），随后对代码侧做模块树、包结构、关键类、迁移脚本、配置、测试报告的实测统计，之后交叉比对形成十四条冲突，最后逐项自检硬指标与占位符。

### 整理原则

1. **逐份精读，不跨文档归并**：摘要按文档自身章节结构组织，不做跨文档的主题重组（那是下游的事）。
2. **出处即章节号**：每条摘要标注 `D编号，§章节`，直接映射回原文位置；代码证据补 `D编号，路径#符号`。
3. **冲突保留**：矛盾信息并列保留多个版本并各带出处，不擅自裁决。
4. **事实驱动**：以原始资料中的事实为准，不添加主观推断；确需推断的条目已标注风险（见 D19，§6 与 §3.1 本角色备注）。
5. **双索引**：设计基线索引记录关键约束、接口契约、数据口径、Exit 与 Gate 定义；代码现状证据索引记录模块树、包结构、关键类、配置与 SQL、前端结构、测试信号，二者供下游按域逐项核对。

---

## 附录 B：解析 Skill

- `docx`：Word 类产品与业务文档
- `pdf`：PDF 类规范、手册、报告
- `pptx`：PPT 类方案与汇报
- `xlsx`：Excel 类数据清单、指标表

**本轮补充说明**：主理人转交的原始资料中不含 docx、pdf、pptx、xlsx 四类文件，模板 Skill 列表按模板原文保留。本轮实际主要使用文件读取与检索能力，辅以命令行统计与脚本化数据提取：

| 真实类型 | 本轮解析方式 |
| --- | --- |
| md | 全文读取或按标题结构逐章精读；行数与章节号由标题行提取 |
| java | 按模块与 api/biz 子模块统计数量，按包路径登记关键类与接口 |
| ts 与 vue | 按目录结构登记，package.json 提取技术栈与脚本 |
| sql | 按版本号登记迁移脚本与双份副本，读取文件头注释与配置引用 |
| drawio 与 png | 仅索引登记，按 DIAGRAM-INDEX 建立覆盖文档映射 |
| yaml 与 yml | 读取 compose 服务清单与 application 配置段 |
| xml | 读取根 POM 与各级 pom 的 modules、版本与插件声明 |
| json | 登记事件 Schema 与契约文件，按文件名与 ADR 映射索引 |
| sh 与 mjs 与 py | 按用途分组登记（验收、图形生成、进度生成、飞书发布、迁移辅助） |
| surefire xml 与 txt | 脚本化提取 tests、failures、errors、skipped 属性并与 progress.md 对账 |

---

> **Gate 纪律**：本 decision 仅表示本角色交付物完整、自校验通过，供主理人触发 G1 自动校验与 AskUserQuestion 人工审核；G1 未由主理人宣布通过前，不视为放行，下游不得消费本摘要进入 research-analyst 或后续阶段。
>
> decision: "资料摘要完成，下游可进入"

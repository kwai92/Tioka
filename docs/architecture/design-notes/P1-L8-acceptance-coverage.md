# P1-L8 验收覆盖：ArchUnit 架构守护与契约测试（CDC）映射

> 范围：把「架构边界 / 契约一致性」类验收准则映射到仓库中**实际存在**的 ArchUnit 规则与离线契约测试，逐条给出 `file:line` 与四态判定；缺口统一登记到 P2 加固建议。
> 基线：`feature/p1-l8-tests`（自 `main@3f2199f`）；模块事实基线 15 个（`tioka-modules/pom.xml`）。
> 结论口径：本文件只做**登记与守护**，不新增/改写既有 ArchUnit 规则，不改冻结库与 `archunit.properties`。

## 0. 四态定义

| 状态 | 含义 |
|---|---|
| ✅ 已守护 | 有可执行断言，违反即构建失败 |
| 🟡 部分守护 | 仅编译期约束 / 静态文件断言 / 仅枚举与 Schema 对齐，未覆盖运行期行为 |
| ⚪ 未守护 | 仅有文档约定或人工评审，无自动化断言（登记为 P2） |
| ➖ 不适用 | 该准则不属于静态架构或契约测试可覆盖范围 |

## 1. ArchUnit 架构守护映射

测试类统一位于 `tioka-server/src/test/java/com/tioka/server/arch/`，生产代码范围 `com.tioka`（`ImportOption.DoNotIncludeTests`）。

### 1.1 全仓分层与安全基线（`TiokaArchitectureTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| A1 | Controller 必须声明 `@RequirePermission` / `@PublicApi` | `TiokaArchitectureTest.java:36` | ✅ | ADR-015 |
| A2 | 仅 model-biz 可依赖模型 HTTP 客户端 | `TiokaArchitectureTest.java:45` | ✅ | ProviderClient 唯一出口 |
| A3 | `LlmClientApi` 不得被依赖（已删除） | `TiokaArchitectureTest.java:52` | ✅ | 统一 ModelGateway（MH-04） |
| A4 | 生产代码不得依赖测试用 `HashEmbeddingClient` | `TiokaArchitectureTest.java:58` | ✅ | MH-04 / KNW-01 |
| A5 | `tioka-server` 只依赖 api/framework，不碰 application/infrastructure/domain | `TiokaArchitectureTest.java:66` | ✅ | ADR-030；ENG-06 已清零，规则即时生效 |
| A6 | application/domain 禁止 `synchronized` | `TiokaArchitectureTest.java:76` | 🟡 | `FreezingArchRule`，冻结库为空违规，等价普通规则但保留冻结外壳 |
| A7 | 禁止 `new ObjectMapper` | `TiokaArchitectureTest.java:84` | ✅ | ENG-05 |
| A8 | 业务 ID 禁止 `UUID.randomUUID` | `TiokaArchitectureTest.java:90` | 🟡 | `FreezingArchRule`；现存 3 处合法非 ID 用途（见 §5） |
| A9 | 仅 runtime 可依赖 `runtime.application` | `TiokaArchitectureTest.java:96` | ✅ | ADR-016「一个内核」 |
| A10 | 请求线程（`@RestController`）不得依赖 ModelGateway | `TiokaArchitectureTest.java:106` | 🟡 | `FreezingArchRule`；ADR-029 第 21 条 |
| A11 | `RuntimeTestHooks` 仅可在 `@Profile("gate")` 注册 | `TiokaArchitectureTest.java:113` | ✅ | 自定义 `ArchCondition`（`:118`） |

### 1.2 M2 静态闸门（`M2ArchitectureTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| B1 | 跨域事件一律经 outbox，业务模块不直连 RocketMQ | `M2ArchitectureTest.java:28` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| B2 | MQ 监听器必须继承 `IdempotentConsumer` | `M2ArchitectureTest.java:43` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| B3 | 仅 connector 可依赖飞书 SDK | `M2ArchitectureTest.java:51` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| B4 | 仅 `CredentialCipher` 可调用 `Cipher.getInstance` | `M2ArchitectureTest.java:64` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| B5 | M2 规则动作不得引用 `OrchActionType.FLOW` | `M2ArchitectureTest.java:74` | ✅ | PK-09 已移出冻结库，普通规则 |

### 1.3 M3 静态闸门（`M3ArchitectureTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| C1 | 执行侧只经只读投影读配置 | `M3ArchitectureTest.java:22` | 🟡 | `FreezingArchRule`；ADR-041 §2 |
| C2 | 配置版本不得引用 SecretStore 类型 | `M3ArchitectureTest.java:40` | 🟡 | `FreezingArchRule`；ADR-042 §1.6 |
| C3 | cedar-java 仅 governance 可用 | `M3ArchitectureTest.java:48` | 🟡 | `FreezingArchRule` |
| C4 | 仅插件宿主可 `new URLClassLoader` | `M3ArchitectureTest.java:60` | 🟡 | `FreezingArchRule`；M3-06 |
| C5 | 仅 connector 可依赖 JDBC 数据集实现 | `M3ArchitectureTest.java:71` | 🟡 | `FreezingArchRule` |
| C6 | `ontology.domain` 不依赖 Spring | `M3ArchitectureTest.java:81` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| C7 | 不得引用 Flow 旧引擎包 | `M3ArchitectureTest.java:89` | 🟡 | `FreezingArchRule`，冻结库空违规 |
| C8 | `ToolCallContext` 仅经工厂构造 | `M3ArchitectureTest.java:101` | 🟡 | `FreezingArchRule` |

### 1.4 模块依赖（`ModuleDependencyArchTest`，命令式 `check(classes)`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| D1 | runtime-biz 不得直接依赖 connector | `ModuleDependencyArchTest.java:27` | ✅ | W0-09 |
| D2 | runtime.application 不得依赖异域 application | `ModuleDependencyArchTest.java:37` | ✅ | `allowEmptyShould(true)` |
| D3 | Flow/Chat 不得绕过 ToolGateway 直调 `McpToolCallApi` | `ModuleDependencyArchTest.java:50` | ✅ | 工具调用统一入口 |
| D4 | 出网必须经 `EgressClient`，禁止裸 `HttpClient` | `ModuleDependencyArchTest.java:60` | ✅ | 仅 `system.application.egress` 例外 |
| D5 | Chat/Flow/Agent 不得依赖废弃 `OrchApi` | `ModuleDependencyArchTest.java:70` | ✅ | 人审走 `OrchAttentionApi` |

### 1.5 持久化边界（`PersistenceArchTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| E1 | 禁止 `spring-jdbc` / `JdbcTemplate` | `PersistenceArchTest.java:39` | ✅ | 统一 MyBatis-Plus |
| E2 | Mapper 接口禁止注解 SQL（含 `*Provider`） | `PersistenceArchTest.java:48` | ✅ | SQL 落 `mapper/*.xml` |
| E3 | application/kernel 不得直持 Mapper | `PersistenceArchTest.java:64` | ✅ | 只依赖 Repository |
| E4 | 无 `workspaceId` 的表必须在豁免清单 | `PersistenceArchTest.java:77` | ✅ | deny-by-default 注入防护 |
| E5 | 有 `workspaceId` 的表不得进豁免清单 | `PersistenceArchTest.java:104` | ✅ | 仅 `sys_member` / `sys_api_key` 例外 |
| E6 | `@TableName` 实体必须继承约定 DO 基类 | `PersistenceArchTest.java:138` | ✅ | 白名单见 `:151-157` |

### 1.6 密钥访问白名单（`SecretStoreArchTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| F1 | 仅白名单包可调用 `SecretStore.get` | `SecretStoreArchTest.java:25` | ✅ | ADR-014 D5 |

### 1.7 模块树基线（本轮新增守护）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| G1 | `tioka-modules/pom.xml` 声明 15 个模块且含 ontology/governance/eval | `P1L8ArchSuiteIntegrityTest.java:54` | ✅ | 防模块树回退 |
| G2 | `docs/architecture/module-dependencies.md` 回写 15 模块（X8 锁） | `P1L8ArchSuiteIntegrityTest.java:73` | ✅ | 文档-代码一致性 |
| G3 | 6 个 ArchUnit 测试类源文件存在 | `P1L8ArchSuiteIntegrityTest.java:87` | ✅ | 防测试被静默删除 |
| G4 | 4 个核心契约测试源文件存在 | `P1L8ArchSuiteIntegrityTest.java:97` | ✅ | 防契约套件退化 |

## 2. 契约测试 / CDC 映射

均为**离线、手写 JSON-Schema 或表驱动**，不依赖 Pact / Spring Cloud Contract，不需要运行中间件。

### 2.1 MQ 信封与事件 Schema（`EnvelopeContractTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| H1 | `docs/contracts/events/*.json` 均为合法 JSON Schema | `EnvelopeContractTest.java:55` | ✅ | 2020-12 方言编译 |
| H2 | 信封不得出现 `tenantId`；必须保留 replayOf/traceparent/workspaceId/requestId/subject | `EnvelopeContractTest.java:77` | ✅ | 约束 F28 / ADR-073 |
| H3 | `DomainEvent` 携带可选 requestId/subject，旧构造仍兼容 | `EnvelopeContractTest.java:95` | ✅ | 表驱动 |
| H4 | `orch-receipt-v1.json` 不得被当作信封扫入 | `EnvelopeContractTest.java:117` | ✅ | 防误配 |
| H5 | 每个 `tioka.*.v*` 事件常量都有同名 payload schema | `EnvelopeContractTest.java:127` | ✅ | 扫描 `*MqTopics.java`，`:154` |
| H6 | 仓库根解析不依赖固定工作目录 | `EnvelopeContractTest.java:176` | ✅ | 向上查找 `docs/contracts/events` + `tioka-modules`；本套件复用同款策略 |

### 2.2 SSE 事件 Schema（`RuntimeEventSchemaContractTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| I1 | Schema `type` 枚举 = `RuntimeEventType` | `RuntimeEventSchemaContractTest.java:35` | ✅ | 顺序与取值双向一致 |
| I2 | Schema turn.status 枚举 = `TurnStatus` | `RuntimeEventSchemaContractTest.java:43` | ✅ | ADR-016 |
| I3 | Schema itemType examples = `ItemType` | `RuntimeEventSchemaContractTest.java:51` | ✅ | 示例即契约 |
| I4 | 接受合法 `item.delta`，拒绝缺 payload | `RuntimeEventSchemaContractTest.java:59` | 🟡 | 仅静态样例校验，非运行时 SSE 输出校验 |
| I5 | 接受通用 approval 事件 | `RuntimeEventSchemaContractTest.java:72` | 🟡 | 同上 |

### 2.3 Turn 状态机（`TurnStatusContractTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| J1 | 19 条状态转换与契约表一致 | `TurnStatusContractTest.java:34` | ✅ | `@CsvSource` 表驱动 |
| J2 | 终态无后继状态 | `TurnStatusContractTest.java:39` | ✅ | |
| J3 | 仅 RUNNING 持有租约 | `TurnStatusContractTest.java:46` | ✅ | |

### 2.4 三套幂等键命名空间（`MessagingIdempotencyKeyContractTest`）

| # | 验收准则 | 守护点 | 四态 | 说明 |
|---|---|---|---|---|
| K1 | MQ `evt_*` eventId 不可被解析为 runtime `EventId(seq)` | `MessagingIdempotencyKeyContractTest.java:35` | ✅ | 离线反射/解析断言 |
| K2 | runtime seq 游标不混入 `evt_` 命名空间 | `MessagingIdempotencyKeyContractTest.java:48` | ✅ | |
| K3 | Turn `clientMsgId`（runtime_turn）与 MQ `eventId`（sys_consumed）分表分字段 | `MessagingIdempotencyKeyContractTest.java:60` | ✅ | 复用既有表，不新增迁移 |

> 注：该类原由 P0（L2/L3）在 `feature/p0-l2-l3-tests` 交付；本分支基线（main）尚未包含，为满足 L8「契约套件存在性守护」按**原文件同步引入**，内容未改写。若 P0 先合入，两处内容一致，合并无冲突。

### 2.5 其他既有契约测试（登记，不纳入 G4 守护）

| 文件 | 主题 |
|---|---|
| `tioka-modules/tioka-module-agent/tioka-module-agent-api/src/test/java/com/tioka/agent/api/spec/AgentSpecContractTest.java` | AgentSpec 结构 |
| `tioka-modules/tioka-module-governance/tioka-module-governance-api/src/test/java/com/tioka/governance/api/config/ConfigVersionApiContractTest.java` | 配置版本 API |
| `tioka-modules/tioka-module-orchestration/tioka-module-orchestration-api/src/test/java/com/tioka/orchestration/api/engine/OrchDecisionContractTest.java` | 编排决策 |
| `tioka-modules/tioka-module-orchestration/tioka-module-orchestration-api/src/test/java/com/tioka/orchestration/api/engine/OrchReceiptContractTest.java` | 回执 |
| `tioka-modules/tioka-module-orchestration/tioka-module-orchestration-api/src/test/java/com/tioka/orchestration/api/watcher/TurnWatcherMessageContractTest.java` | TurnWatcher 消息 |
| `tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz/src/test/java/com/tioka/orchestration/application/decide/OrchStateMachineContractTest.java` | 编排状态机 |
| `tioka-modules/tioka-module-connector/tioka-module-connector-api/src/test/java/com/tioka/connector/api/biz/LeaseBizOpenApiContractTest.java` | Connector OpenAPI |
| `tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/test/java/com/tioka/connector/sdk/ConnectorSdkContractTest.java` | Connector SDK |
| `tioka-server/src/test/java/com/tioka/server/runtime/RuntimeApiContractTest.java` | Runtime API |

## 3. 四态汇总

| 状态 | 数量 | 条目 |
|---|---|---|
| ✅ 已守护 | 40 | A1–A5、A7、A9、A11（8）；B5（1）；D1–D5（5）；E1–E6（6）；F1（1）；G1–G4（4）；H1–H6（6）；I1–I3（3）；J1–J3（3）；K1–K3（3） |
| 🟡 部分守护 | 17 | A6、A8、A10（3）；B1–B4（4）；C1–C8（8）；I4–I5（2） |
| ⚪ 未守护（P2） | 4 | §4 的 P2-1～P2-4 |
| ➖ 不适用 | 0 | — |

逐组计数：

- A 组（`TiokaArchitectureTest`）：✅ 8 / 🟡 3
- B 组（`M2ArchitectureTest`）：✅ 1 / 🟡 4
- C 组（`M3ArchitectureTest`）：🟡 8
- D 组（`ModuleDependencyArchTest`）：✅ 5
- E 组（`PersistenceArchTest`）：✅ 6
- F 组（`SecretStoreArchTest`）：✅ 1
- G 组（`P1L8ArchSuiteIntegrityTest`）：✅ 4
- H 组（`EnvelopeContractTest`）：✅ 6
- I 组（`RuntimeEventSchemaContractTest`）：✅ 3 / 🟡 2
- J 组（`TurnStatusContractTest`）：✅ 3
- K 组（`MessagingIdempotencyKeyContractTest`）：✅ 3

合计 ✅ 40 + 🟡 17 + ⚪ 4 = **61 条验收点**。

## 4. P2 加固建议（本轮不实现）

### P2-1 全局「biz 仅依赖 api」ArchUnit 规则（高优先）

- **现状**：`docs/architecture/module-dependencies.md` §3 规定各 `*-biz` 只能依赖其他模块 `*-api` / framework，但仅有 Maven 编译期依赖约束；`ModuleDependencyArchTest` 只覆盖 runtime→connector 与少量 application 互依，**没有全局规则**。
- **建议**：新增 `noClasses().that().resideInAPackage("com.tioka..")...` 形式的全局断言：任一 `com.tioka.<mod>.application|infrastructure|domain..` 不得依赖 `com.tioka.<other>.application|infrastructure|domain..`（白名单 `server`、`framework`、同模块）。
- **落地方式**：首轮用 `FreezingArchRule` 冻结存量违例，随后按模块清零并移出冻结库；**必须同步把 `archunit.properties` 的 `allowStoreUpdate` 临时置 true 生成基线，再改回 false**，否则 `allowStoreCreation=false` 会导致构建失败。
- **风险**：跨模块 `application` 直连存量未知，直接加普通规则大概率误伤，故本轮不加。

### P2-2 skill / eval 模块边界

- **现状**：`skill`、`eval` 两个模块没有任何 ArchUnit 规则；M3 规则覆盖 governance/ontology，但未覆盖 eval 的评测执行边界（如 eval 不得依赖 runtime.kernel 内部实现）。
- **建议**：先梳理两模块的允许依赖矩阵，再补 `skill` 只依赖 system-api/framework、`eval` 只依赖 runtime-api/system-api/framework 的普通规则；确认零违例后再加，否则先冻结。

### P2-3 运行时 SSE 输出校验

- **现状**：`RuntimeEventSchemaContractTest` 只校验 Schema 与枚举对齐及**静态样例**（I4/I5），不校验真实 SSE 流；运行时若产生未注册事件类型或字段漂移，契约测试无法发现。
- **建议**：在 `tioka-server` 集成测试中对真实 `/api/.../stream` 输出逐帧做 `runtime-event-v1.json` 校验（可用 Testcontainers/WebTestClient），失败即阻断；或增加生产侧序列化出口的 schema 断言开关（默认关闭，gate profile 打开）。

### P2-4 冻结库清零排期

- **现状**：`archunit_store/` 共 15 条冻结规则（`stored.rules` 15 行），对应 15 个 UUID 文件；其中 **14 个为空违规**、仅 `noTruncatedUuid` 有 3 条存量（`0a28620b-…`）。空库 + `FreezingArchRule` 外壳会让「已清零规则」与「仍需冻结规则」难以区分。
- **建议**：按规则逐条排期，把已清零的 `FreezingArchRule.freeze(...)` 降级为普通规则并从 `stored.rules` 删除对应行；保留的 3 处 `UUID.randomUUID` 非 ID 用途（`McpAdminService.java:251`、`TokenService.java:35`、`OidcFederationService.java:132`）要么改用 `Ids.newId`，要么在规则说明中固化为例外，避免冻结库长期漂移。
- **注意**：本轮**禁止**修改 `archunit_store/` 与 `archunit.properties`，排期需在独立变更中执行并同步评审。

## 5. 验证命令

```bash
# 新增守护测试（-am 会把 -Dtest 传到上游模块，需忽略无匹配测试）
mvn -q -pl tioka-server -am -DskipTests=false \
    -Dtest=P1L8ArchSuiteIntegrityTest \
    -Dsurefire.failIfNoSpecifiedTests=false test

# 既有架构套件回归（模块依赖 + 持久化 + 密钥白名单；ArchUnit @AnalyzeClasses 类由 surefire 直接执行）
mvn -q -pl tioka-server \
    -Dtest=TiokaArchitectureTest,ModuleDependencyArchTest,PersistenceArchTest,SecretStoreArchTest test
```

> 实测结果记录于对应提交说明；`M2ArchitectureTest` / `M3ArchitectureTest` 同样建议在完整 `mvn -pl tioka-server test` 中回归。

## 6. 与一致性矩阵的对应

- **X8（模块树 13 vs 15）**：由 G1/G2 锁定事实基线 15，文档已回写；G1/G2 失败即表示代码或文档回退。
- **ArchUnit 冻结库退化风险**：登记于 P2-4，本轮不改动冻结库。
- **「CDC」命名口径**：现有契约测试为手写 Schema/表驱动，不是 Pact/Spring Cloud Contract 工具链；对外表述应称「契约测试（离线）」，工具化 CDC 属 P2/P3 范畴。

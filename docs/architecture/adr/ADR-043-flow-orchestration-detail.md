# ADR-043 — Flow 工作流编排详细设计

- 状态：Accepted（设计定案，待实现；排期已于 2026-11-06 由负责人选定方案 A，见第 16 节）
- 日期：2026-11-06
- 覆盖工作项：细化 FLW-04〜FLW-08；新增 FLW-09〜FLW-19、FE-12〜FE-14
- 相关：[ADR-019](ADR-019-flow-engine-publication.md)（本 ADR 在其基础上细化，冲突处以本 ADR 为准）、[ADR-020](ADR-020-orchestration.md)、[ADR-023](ADR-023-agent-skill-assets.md)、[ADR-025](ADR-025-governance.md)、[ADR-031](ADR-031-agent-eval-experience.md)、[ADR-033](ADR-033-reversible-actions.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-037](ADR-037-structured-outputs.md)、[ADR-038](ADR-038-multi-agent-team.md)〜[ADR-042](ADR-042-provider-credentials-model-params.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准（本 ADR 对应第 64 条）；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格描述字段、接口与规则，不附代码、SQL 或 Java 片段；契约文件在实现前的契约 PR 里由团队按本 ADR 编写。

## 背景

2026-11-06 负责人问：「关于工作流流程编排，有没有更详细的设计？」对照现有文档，Flow 层（对标 Langflow：画布、节点与组件生态、发布）只有一份骨架：

| 已有 | 内容 | 留白 |
| --- | --- | --- |
| ADR-019 | 数据库驱动的节点调度器、节点状态、汇合与跳过传播、挂起续跑、节点 SPI、三种发布形态、表结构 | 没有 DSL 规范、端口类型、表达式语言；节点目录只有 12 个旧节点；超时、取消、副作用、崩溃恢复规则不全；调试、观测、版本、权限都没有展开 |
| M1-03、M1-04、M2-04 | FLW-02 LLM 节点走网关、FLW-01 汇合修复、FLW-03 人工节点挂起 | 都是对旧引擎的局部改造；M2-04 明确说 M3 要按 ADR-019 重写引擎 |
| M3-02、M3-03 | DAT-04 Flow 透传授予、FLW-08 map 节点、@model.build 模板展开 | FLW-08 假定「FLW 现有派发器」已存在，但**重写引擎本身没有工作项，也没有人日估算** |
| ADR-038〜042 | Flow 中的 Agent 节点计一层委派；Prompt 钉版本、模型别名跟随；Flow LLM 节点是参数覆盖第 3 层 | Flow 自身的版本规则、引用钉版本的范围、与统一版本框架的接法没有写 |
| 34 号计划 | FLW-04〜FLW-07 列在 M3，没有人日 | 无 |

结论：要让团队能照着实现，需要补齐 DSL、节点目录、引擎细则、版本、发布、权限、调试、观测、与其他层的衔接，并补上「引擎重写」这个隐含但未估算的工作项。

## 决定

1. **Flow 只做「一次 Run 内的确定性流程」**。触发、定时、Webhook、事件订阅一律归 Orchestration（ADR-020），Flow 不另建触发器和定时器；多 Agent 自主协作归 ADR-038。三者的分工见第 1 节。
2. **DSL 是唯一真相**。画布只是 DSL 的编辑器，布局信息单独存放、不进版本哈希。DSL 有正式的端口类型系统，表达式统一用 CEL（与编排规则、本体要点一致）。
3. **引擎按 ADR-019 重写为数据库驱动的事件调度器**，新立工作项 FLW-09。本 ADR 补齐超时、取消、错误路由、副作用节点的崩溃处理（`UNCERTAIN`）、大载荷外置、流式事件。
4. **Flow 是发布型对象，接入 ADR-041 统一版本框架**（保留自有的 `flow_draft`、`flow_version` 表，用适配器接入历史、对比、引用、影响分析界面，与 Agent 相同）。决定行为的引用钉版本，模型别名与知识库跟随。
5. **发布形态增加 INTERNAL**，供编排规则、子流程节点和平台模板（@model.build）调用；含人工节点或延时节点的 Flow 在 M3 不能发布为 Agent 工具或 MCP 工具。
6. **运行身份由入口决定，不随发布者**。发布时用发布者权限校验引用可用，运行时用调用方身份或授予逐次检查，Flow 不能成为提权通道（延续 ADR-029 第 47 条）。
7. **调试默认不产生真实副作用**。草稿试运行中，带副作用的工具只做 `prepare`（ADR-033），不 `commit`，除非编辑者逐次勾选「真实执行」。
8. **观测以节点为最小单位**。每个节点运行是一个 span，用量与成本精确归到节点，统计按发布版本切片（ADR-041 原则）。

---

## 1. 定位与边界

### 1.1 三种编排方式的分工

| 场景特征 | 用什么 | 理由 |
| --- | --- | --- |
| 步骤固定、要可视化编辑、要可复用与发布 | **Flow** | 步骤与数据流是确定的，可测试、可评测、可版本化 |
| 由外部事件或定时触发、需要归单、去重、并发控制、人审升级 | **Orchestration**（规则动作可调用 Flow 发布） | 编排负责「什么时候、对哪个工单、以谁的身份」跑；Flow 负责「怎么跑」 |
| 步骤不固定、要 Agent 自主规划与分派 | **多 Agent 团队**（ADR-038） | 规划交给主管 Agent；Flow 可作为其中一个工具 |
| 单个 Agent 内的多步工具调用 | **Runtime Turn**（ADR-016） | 不需要额外编排 |

### 1.2 Flow 的入口

Flow 本身没有触发器。所有 Run 都来自下表的某个入口，入口决定 `origin` 与运行身份（第 8.2 节）。

| 入口 | origin | 说明 |
| --- | --- | --- |
| Studio 试运行 | `studio` | 运行草稿快照，标记为调试 Run，不计入生产统计 |
| 发布为 API | `api` | 同步或异步调用，第 7 节 |
| 发布为 Agent 工具 | `tool` | Agent 在 Turn 中调用 `flow.{name}` |
| 发布为 MCP 工具 | `mcp` | 外部 MCP 客户端经 OAuth 调用（CON-05） |
| 编排规则动作 | `orch` | 规则命中后以规则服务账号调用；定时、Webhook、IM 消息都经编排进入 |
| 子流程节点 | `subflow` | 父 Run 中的 SubFlow 节点调用 |
| 批次评测 | `eval` | ADR-031，工具默认走桩或只读 |
| 平台服务 | `platform` | 例如本体模块触发 @model.build（INTERNAL 发布） |

---

## 2. 概念模型

| 概念 | 说明 | 存储 |
| --- | --- | --- |
| Flow | 一个工作流的身份：名称、所属空间、负责人、可见性 | `flow_draft` 的头部字段（沿用现表） |
| 草稿 | 每个 Flow 一份可编辑 DSL + 画布布局 + 调试数据；乐观锁 `revision` | `flow_draft` |
| 版本 | 不可变 DSL 快照，含解析后的钉版本引用、输入输出 Schema、`requiredCapabilities` | `flow_version` |
| 发布 | 版本的对外形态（API、TOOL、MCP、INTERNAL），带指向某个版本的指针 | `flow_publication` |
| Run | 一次执行，绑定某个版本（或草稿快照），记录入口、身份、授予 | `flow_run` |
| 节点运行 | 一个节点的一次执行；Loop 每轮、map 每个元素各一条，重试记 `attempt` | `flow_node_run` |
| 节点类型 | 节点的端口、配置 Schema、执行器、副作用级别、版本 | 代码与插件注册，不建表；远程节点另有登记表 |

---

## 3. DSL 规范（FLW-10）

### 3.1 文档结构

DSL 以 JSON 存储（规范化后计算哈希），Studio 支持 YAML 导入导出。M3-03 中的 YAML 示意即本格式的 YAML 写法。

| 段 | 必填 | 内容 |
| --- | --- | --- |
| `schemaVersion` | 是 | DSL 格式版本，M3 为 1。格式升级时由迁移器把旧版本草稿升级，已发布版本保持原格式、引擎按原格式执行 |
| `meta` | 是 | 名称、说明、标签、图标、负责人 |
| `inputs` | 是 | Run 的输入定义，由 Input 节点生成：字段名、端口类型、是否必填、默认值、说明 |
| `outputs` | 是 | Run 的输出定义，由 Output 节点生成 |
| `constants` | 否 | 只读常量（不得放凭证），表达式中以 `const.x` 引用 |
| `settings` | 否 | Run 级设置：Run 超时、节点并发上限、默认重试、数据保留天数、是否允许流式 |
| `nodes` | 是 | 节点列表，第 3.2 节 |
| `edges` | 是 | 连线列表，第 3.3 节 |
| `subgraphs` | 否 | Loop 与 map 的循环体，结构与顶层相同，以 ID 被节点引用 |

不进 DSL、单独存放在草稿中的：画布布局（坐标、折叠、分组框、注释）、调试数据（固定输出、断点、最近一次调试输入）。它们不影响执行，也不进版本哈希。

### 3.2 节点字段

| 字段 | 说明 |
| --- | --- |
| `id` | 图内唯一，小写字母、数字、下划线，最长 64；表达式按它引用输出 |
| `type`、`typeVersion` | 节点类型与版本，例如 `llm` / `1`。发布时钉住 |
| `label` | 显示名 |
| `config` | 节点配置，按该类型的配置 Schema 校验；字符串字段可以写 `${...}` 表达式 |
| `inputs` | 输入端口取值：来自连线，或直接写表达式 |
| `joinMode` | `ALL`（默认）或 `ANY`，ADR-019 |
| `when` | 可选的前置条件表达式，为假时节点直接 SKIPPED 并向下传播 |
| `timeout` | 节点超时，缺省取类型默认值（第 14 节） |
| `retry` | 最大次数、退避方式（固定或指数）、初始间隔、只对哪些错误码重试 |
| `onError` | `FAIL_RUN`（默认）、`CONTINUE`（输出为空并附错误对象，取代 ADR-019 的 `continueOnError`）、`ROUTE`（走 `error` 输出端口） |
| `stream` | 是否把该节点的增量输出推到 Run 事件流（只对 LLM、Agent 有效） |
| `labels` | 可选的数据标签下限（ADR-040），例如强制把输出标为 CONFIDENTIAL |

### 3.3 连线与端口

- 连线字段：起点节点与端口、终点节点与端口、可选条件 `when`（只在 Branch、Switch 的输出端口上有意义）。
- 每个节点类型声明输入端口和输出端口。默认输出端口名为 `out`；支持错误路由的节点另有 `error` 端口；Branch 有 `true`、`false`；Switch 按配置生成多个 `case_*` 端口与 `default`。
- 一个输入端口只能接一条连线，多路汇合用 Merge 节点或 `joinMode`；一个输出端口可以接多条连线。

### 3.4 端口类型系统

| 类型 | 说明 |
| --- | --- |
| `text` | 字符串 |
| `number`、`boolean` | 数值、布尔 |
| `json` | 任意 JSON；可附 Schema 引用（ADR-037 的 Schema 登记表，如 `lease.contract.statement@1`） |
| `list<T>` | 元素类型为 T 的数组，map 节点的 `items` 必须是它 |
| `file` | 对象存储引用 + MIME + 大小 |
| `document` | 文档层引用（DOC-01，含证据单元） |
| `messages` | 对话消息列表（角色 + 内容），用于 LLM 节点 |
| `chunks` | 检索结果（分块、得分、来源、引用信息） |
| `any` | 不检查，画布显示警告 |

兼容规则（发布时检查，画布上实时提示）：

| 源 → 目标 | 结果 |
| --- | --- |
| 类型相同 | 通过 |
| `number`、`boolean` → `text` | 自动转换 |
| 带 Schema 的 `json` → 带 Schema 的 `json` | 结构检查：目标的必填字段在源 Schema 中都存在且类型兼容，否则报错 |
| `text` → `json` | 报错，需经 Parser 或 Transform 节点 |
| 任意 → `any`、`any` → 任意 | 通过并警告 |
| `list<A>` → `list<B>` | 按 A → B 的规则 |

### 3.5 表达式

- 语言：CEL，写在字符串字段的 `${...}` 中；整字段只有一个表达式时保留原类型，否则拼接为字符串。
- 可引用：`input.字段`、`nodes.节点ID.端口`（端口为 `out` 时可省略）、`const.名`、`run.id`、`run.origin`、`run.caller`（只含类型和 ID）、循环体内的 `item`、`index`、`iteration`。
- 不可引用：凭证、环境变量、其他 Run 的数据。表达式不能发起网络调用或读写存储。
- 内置扩展函数：JSON 路径取值、字符串处理、按业务时区（默认 Asia/Shanghai）的日期计算、列表的过滤与映射、长度、默认值。
- 每次求值有成本上限，超限时该节点失败，错误码 `FLOW_EXPR_LIMIT`。
- 发布时对所有表达式做编译与类型推断；引用了不存在的节点或端口、引用了下游节点，都是发布错误。

### 3.6 子图

Loop 与 map 的循环体放在 `subgraphs` 中，有自己的 Input、Output 节点。子图内部同样必须无环；子图可以嵌套，嵌套深度上限 3。子图内节点的运行记录通过 `parent_node_run_id` 挂在 Loop 或 map 节点下。

### 3.7 发布校验清单

发布（第 6.2 节）前逐项检查，任一错误阻止发布，警告可确认后继续：

| 类 | 检查项 | 级别 |
| --- | --- | --- |
| 结构 | 顶层与子图无环；恰好一个 Input、至少一个 Output；没有从 Input 不可达的节点；子图嵌套 ≤ 3 | 错误 |
| 类型 | 每个节点 config 符合类型 Schema；端口类型兼容；表达式编译通过 | 错误 |
| 引用 | 模型别名存在且用途匹配；Prompt 模板版本已发布且变量齐全（ADR-041）；Agent、子流程、MCP 快照、知识库、Schema、凭证引用都存在 | 错误 |
| 权限 | 发布者对所有引用资源有 `USE`（第 8.3 节） | 错误 |
| 子流程 | 展开全部钉版本的子流程后无循环调用，总嵌套 ≤ 5 | 错误 |
| 限制 | Loop 设了最大轮数；节点数 ≤ 200；超时不超过上限 | 错误 |
| 发布形态 | 含 HumanCard、Delay 或 Agent 节点的 Flow 发布为 TOOL 或 MCP 时，最长执行时间不能超过工具同步上限（第 7.3 节） | 错误 |
| 质量 | 存在 `any` 端口；存在调试用固定输出（不会进版本，仅提示）；节点类型已标记弃用 | 警告 |

---

## 4. 节点目录（FLW-11）

### 4.1 节点清单

副作用级别沿用 ADR-033：`NONE`（纯计算）、`READ`（只读外部）、`WRITE`（写外部，可预演）、`EXTERNAL`（对外发送，不可撤回）。「等待」表示节点会进入 WAITING 并释放 worker。

| 类别 | 节点 | 作用 | 执行方式 | 副作用 | 阶段 | 现有代码对应 |
| --- | --- | --- | --- | --- | --- | --- |
| 输入输出 | Input | 定义 Run 输入 | 同步 | NONE | M3 | IO |
| | Output | 定义 Run 输出，可有多个（按分支只走其一） | 同步 | NONE | M3 | Output |
| 模型 | Prompt | 渲染 Prompt 模板（引用模板@版本或内联） | 同步 | NONE | M3 | Prompt |
| | LLM | 经 ModelGateway 调用模型别名，支持参数覆盖与流式 | 同步 | READ | M3 | LLM |
| | StructuredExtract | 带 OutputContract 的模型调用，输出受 Schema 约束并自动修复（ADR-037） | 同步 | READ | M3 | 新增 |
| | Embedding | 生成向量 | 同步 | READ | M3 | Embedding |
| Agent | Agent | 调用一个 Agent 跑一个完整 Turn（多步工具） | 等待 | 取决于 Agent 的工具 | M3 | Agent |
| 知识 | Retrieve | 知识库检索（含 Rerank），按运行身份做 ACL | 同步 | READ | M3 | Retrieve |
| 工具 | Tool | 调用 ToolGateway 上的任意工具（内置、业务插件、`flow.*`） | 同步 | 取决于工具 | M3 | 新增 |
| | MCP | 调用某个 MCP Server 快照中的工具 | 同步 | 取决于工具 | M3 | MCP |
| | HTTP | 调用外部 HTTP 接口，凭证用引用 | 同步 | 按方法声明：GET 为 READ，其余为 WRITE | M3 | HTTP |
| 数据 | Transform | 用 CEL 做字段映射、过滤、拼装 | 同步 | NONE | M3 | 新增 |
| | Parser | 文本解析为 JSON、按分隔符切分、提取代码块 | 同步 | NONE | M3 | Parser |
| | Merge | 把多个输入合成对象或列表 | 同步 | NONE | M3 | 新增 |
| 控制 | Branch | 二路条件 | 同步 | NONE | M3 | Branch |
| | Switch | 多路条件，按顺序匹配，带 default | 同步 | NONE | M3 | 新增 |
| | Loop | 有界循环，子图每轮一组节点运行；可设退出条件 | 同步调度 | 取决于子图 | M3 | Loop |
| | Map | 并行处理列表，FLW-08 | 同步调度 | 取决于子图 | M3 | 新增（M3-03） |
| | SubFlow | 调用另一个 Flow 的 INTERNAL 或其他发布，钉版本 | 等待 | 取决于子流程 | M3 | 新增 |
| 人机 | HumanCard | 发卡片给人审或补充信息，通用审批表（ADR-029 第 1 条） | 等待 | NONE | M3 | HumanCard |
| | Delay | 等待一段时间或到某个时刻 | 等待 | NONE | M3 | 新增 |
| 集成 | EmitEvent | 向编排发一条事件（经 outbox），用于 Flow 结束后触发其他规则 | 同步 | WRITE（平台内部） | M3 | 新增 |
| | Notify | 经通知渠道（NTF，飞书、邮件）发消息 | 同步 | EXTERNAL | M3 | 新增 |
| 扩展 | 平台节点 | 领域包或平台模块注册，例如本体的 ParseDocs、ExtractObject | 由注册方声明 | 由注册方声明 | M3 | 新增（M3-03） |
| | 插件节点 | Java 插件 jar，签名后加载（FLW-05） | 同步或等待 | 声明 | M3 | 新增 |
| | 远程节点 | 外部 HTTP 服务按约定协议实现（FLW-05） | 同步或回调等待 | 声明 | M3 | 新增 |
| | Script | Python/JS 脚本，进沙箱（RT-11） | 同步 | 由沙箱策略限制 | M4 | 新增 |
| 画布 | Note、Group | 注释与分组框，只存布局，不执行 | — | — | M3 | — |

### 4.2 关键节点规则

| 节点 | 规则 |
| --- | --- |
| LLM | 配置：模型别名（跟随，推荐引用 `sys.flow` 或业务别名）、消息（system、user，可来自 Prompt 节点）、参数覆盖（ADR-042 第 3 层，只能覆盖 binding 允许的参数）、是否流式。调用 ModelGateway 时 `purpose=flow`，上下文带 Run ID、节点运行 ID、发布 ID 与版本号 |
| StructuredExtract | 在 LLM 的基础上加输出 Schema 引用（Schema 登记表，钉版本）与修复次数（默认 2）。修复用尽仍不合法时输出 `valid=false` 与错误清单，由 `onError` 决定后续 |
| Agent | 引用 Agent ID，发布时钉住当前已发布的 Agent 版本（可选「跟随」，见第 6.3 节）；输入映射为用户消息与结构化变量；每个节点运行新建一个 thread（ADR-029 第 11 条），`origin=flow`；输出为最终文本，若 Agent 声明了输出契约则同时输出 `data`；计一层委派（ADR-038、ADR-039），节点授予 = Run 授予 ∩ Agent 授权书 |
| Tool、MCP | 工具名与参数映射；MCP 节点钉住 Server 快照 ID（ADR-023）。调用经 ToolGateway，WRITE 与 EXTERNAL 工具走 `prepare` → 策略与审批 → `commit`（ADR-033）；需要审批时节点进入 WAITING |
| HTTP | 方法、URL（可含表达式，但主机部分必须是常量，发布时校验出网白名单）、请求头、正文、凭证引用、超时、可接受的状态码；响应超过 2 MB 截断并报错 |
| Switch | 按顺序求值各 case 条件，第一个为真的端口被选中，其余标记跳过；都不满足时走 default，没有 default 则节点失败 |
| Loop | 必填最大轮数（≤ 1000）；可设退出条件（每轮结束后求值）；每轮的子图输出累积为列表，或只保留最后一轮（可选） |
| Map | 沿用 M3-03 第 14 节：`items`、`concurrency`（默认 4，上限 32）、`onItemError`（FAIL、SKIP、COLLECT）；结果按 `item_index` 排序 |
| SubFlow | 引用 Flow 发布（任意形态），发布父 Flow 时解析并钉住子流程版本；子 Run 的 `origin=subflow`，继承父 Run 的身份与授予；子 Run 的失败按父节点 `onError` 处理 |
| HumanCard | 卡片模板、收件人（用户、角色、或表达式给出的用户 ID）、表单字段、超时与超时动作（`fail` 或 `default`，M2-04）；调试 Run 中收件人默认改为编辑者本人 |
| EmitEvent | 事件类型、`subject_key`、可信属性 `attrs`（只能来自 DSL 常量或上游节点输出，编排规则按 S0 硬边界只看 `attrs`）、正文；跳数在调用方事件的基础上 +1 |
| Delay | 固定时长或绝对时刻；节点写 `wake_at` 后 WAITING，由唤醒器到点置为 READY；最长 30 天 |

### 4.3 节点生态

| 来源 | 加载方式 | 信任 | 版本 |
| --- | --- | --- | --- |
| 内置 | 随平台发布 | 平台 | 随平台版本；破坏性修改升 `typeVersion` |
| 平台节点 | 平台模块或领域包经插件宿主（ADR-026）注册 | 平台或已安装的领域包 | 随领域包版本 |
| Java 插件 | 插件目录加载签名 jar，实现节点类型 SPI | 需管理员安装，签名校验 | 插件声明 |
| 远程节点 | 管理员登记外部服务地址与凭证引用；平台调用其「描述」接口获取端口与配置 Schema 并存快照，执行时调用「执行」接口；长任务可回调 | 按出网白名单与凭证管理 | 每次登记或刷新产生新的 Schema 快照，Flow 发布时钉住快照 |
| Script | M4，进沙箱 | 沙箱策略 | 随 Flow 版本 |

节点类型的生命周期：`ACTIVE` → `DEPRECATED`（新草稿不可再拖入，已有版本照常执行，发布时警告）→ `REMOVED`（只有在没有任何已发布版本引用时才能移除）。

---

## 5. 执行引擎（FLW-09）

### 5.1 组件

| 组件 | 职责 |
| --- | --- |
| 准入 | 创建 Run：校验身份与授予、配额、发布状态、输入 Schema；超过空间并发上限的 Run 进入 QUEUED |
| 调度器 | 任一节点到达终态时，在同一事务内计算下游节点是否 READY（汇合、跳过传播、`when` 条件），写节点运行行；提交后把 READY 节点推入派发队列 |
| 派发 | 复用 Runtime 的 Redis Stream 派发与租约基础设施；派发时检查 Run 级节点并发上限 |
| worker | 领取节点、续租（30 秒）、以 Run 的 `run_as` 和 `grant_id` 恢复身份执行，写结果 |
| 唤醒器 | 消费 `approval.decided`、`turn.completed`、`turn.failed`、子 Run 结束事件，把对应 WAITING 节点推进；同时处理 `wake_at` 到期的节点（Delay、重试退避） |
| 回收器 | 每 10 秒扫描租约过期的 RUNNING 节点和派发丢失的 READY 节点（第 5.7 节） |
| 补偿扫描 | 每 60 秒检查 WAITING 节点所等待的对象是否已经结束，补偿丢失的唤醒事件 |

### 5.2 状态机

Run 状态：`QUEUED → RUNNING ⇄ WAITING → SUCCEEDED | FAILED | CANCELLED`，另有 `UNCERTAIN`。WAITING 表示当前没有可运行节点、但有节点在等审批、等 Turn、等子 Run 或等时间。

节点状态：`PENDING → READY → RUNNING → SUCCEEDED | FAILED | SKIPPED | CANCELLED`，另有 `WAITING` 与 `UNCERTAIN`。重试退避期间节点处于 WAITING（`wake_at` 为下次重试时间），`attempt` 递增。

| 状态 | 进入条件 | 退出 |
| --- | --- | --- |
| Run `UNCERTAIN` | 某个 WRITE 或 EXTERNAL 节点在执行中失去租约，且其工具没有声明幂等 | 只能由人在审批台判定：重跑该节点、标记成功并继续、或终止 Run。系统不自动重试（与 ADR-020 一致） |
| Run `CANCELLED` | 调用方取消、超时、身份被禁用、发布被紧急停用 | 终态 |

### 5.3 调度规则

在 ADR-019 的规则（跳过传播、`joinMode`、Loop 子图）之上补充：

- `when` 条件为假的节点直接 SKIPPED，与入边全部被跳过的效果相同，并向下传播。
- `onError=ROUTE` 的节点失败时，`out` 端口的下游按「已跳过」处理，`error` 端口的下游被激活；节点本身记为 FAILED，但不导致 Run 失败。
- `joinMode=ANY` 的节点在第一条上游成功时运行，其余上游继续执行，迟到的输出不再传给它，也不取消那些上游。
- Run 结束判定：没有 READY、RUNNING、WAITING 的节点时结束。任一 Output 节点成功则 Run 成功；有节点按 `FAIL_RUN` 失败则 Run 失败。
- Output 节点有多个时（不同分支各有一个），以实际执行的那个为 Run 输出；同时执行了多个时按画布上的顺序合并，同名字段后者覆盖并警告。

### 5.4 并发与准入

| 层级 | 上限 | 超出时 |
| --- | --- | --- |
| 单个 Run 内同时 RUNNING 的节点 | 默认 4，`settings` 可调到 16 | 节点保持 READY 排队 |
| map 节点内同时执行的元素 | 默认 4，上限 32 | 元素排队（不占 Run 级名额） |
| 空间同时运行的 Run | 默认 50，空间设置可调 | 新 Run 进入 QUEUED，按先到先服务 |
| 单个发布的调用速率 | 发布上配置（第 7.2 节） | API 返回 429 |
| 模型与工具 | 由 ModelGateway 的配额、限流和供给侧预算控制（ADR-017、ADR-041） | 按网关规则拒绝或跳过候选 |

Run 进入 WAITING 时不占空间并发名额（与 ADR-029 第 7 条「`WAITING_APPROVAL` 不占并发」一致）。

### 5.5 超时、重试与取消

- 节点超时到期：同步节点中断执行并按失败处理（错误码 `FLOW_NODE_TIMEOUT`，可重试）；等待型节点按类型处理：HumanCard 走自己的超时动作，Agent 节点取消对应 Turn，SubFlow 取消子 Run。
- Run 超时（`settings`，默认 24 小时；含 HumanCard 或 Delay 时最长 30 天）到期后 Run 取消。
- 重试只针对可重试错误（网络、限流、模型 5xx、超时）；参数错误、权限拒绝、配额耗尽、Schema 不合法不重试。默认重试 2 次、指数退避从 2 秒开始，上限 5 次。
- 取消传播：Run 取消时，所有 RUNNING 节点收到取消信号（复用 Runtime 的跨实例取消），WAITING 节点对应的审批撤回、Turn 取消、子 Run 取消；已经 `commit` 的副作用不回滚，只在时间线上列出。
- 运行身份被禁用或授予被撤销时，下一个节点开始前检查失败，Run 取消（ADR-029 第 6 条）。

### 5.6 副作用与幂等

- 每次节点尝试有幂等键：Run ID + 节点 ID + 轮次 + 元素序号 + 尝试次数。Tool、MCP、HTTP 节点把它传给 ToolGateway 或作为请求头 `Idempotency-Key` 发出。
- WRITE、EXTERNAL 工具走 ADR-033 的 `prepare` / `commit`。审批人看到的变更单附带 Flow 名称、版本与节点名。
- API 调用方可以传 `Idempotency-Key`，24 小时内同一个键返回同一个 Run。

### 5.7 崩溃恢复

| 情况 | 处理 |
| --- | --- |
| READY 节点推入队列后丢失 | 回收器发现 READY 超过 30 秒未被领取，重新推入 |
| RUNNING 节点租约过期，节点副作用级别为 NONE 或 READY，或工具声明幂等 | 重新置为 READY，`attempt` +1 |
| RUNNING 节点租约过期，节点为 WRITE 或 EXTERNAL 且工具未声明幂等 | 若 `prepare` 尚未 `commit`，作废后重新 READY；若已进入 `commit`，节点与 Run 置为 UNCERTAIN |
| WAITING 节点 | 不持有租约，状态全在库中，重启不受影响；丢失的唤醒事件由补偿扫描补上 |
| map 元素 | 只重跑未完成的元素（M3-03） |

### 5.8 数据传递与大载荷

- 节点输出 ≤ 256 KB 时内联存放在 `flow_node_run`；超过时写入对象存储，节点运行只存引用。单个值上限 20 MB，超过时节点失败（错误码 `FLOW_PAYLOAD_TOO_LARGE`）。
- 文件与文档只传引用（`file`、`document` 类型），不在节点间复制内容。
- 每个节点输出带数据标签（第 8.5 节），对象存储中的载荷按标签决定是否加密存放。

### 5.9 同步、异步与流式

- Run 事件：`run.queued`、`run.started`、`node.started`、`node.delta`（只对 `stream=true` 的节点）、`node.waiting`、`node.completed`、`node.failed`、`node.skipped`、`run.waiting`、`run.completed`、`run.failed`、`run.cancelled`。
- 事件经 Redis Stream 推送，支持 `Last-Event-ID` 续传；Stream 过期后从库中补节点级事件（`node.delta` 不入库，与 ADR-029 第 3 条的做法一致）。
- Studio 画布用这套事件实时着色；API 异步模式返回 Run ID 与事件流地址。

---

## 6. 版本（FLW-12）

### 6.1 与统一版本框架的关系

Flow 属于 ADR-041 的「发布型」对象。与 Agent 一样保留自己的 `flow_draft` 与 `flow_version`，通过适配器接入统一的历史、对比、引用查询、影响分析界面（GOV-12、FE-07），不迁移到 `cfg_version`。

### 6.2 生命周期

| 动作 | 规则 |
| --- | --- |
| 保存草稿 | 带 `revision`，冲突返回 409（FLW-07）；保留最近 20 次保存的快照，可在草稿内回退；画布每 10 秒在本地自动暂存 |
| 校验 | 第 3.7 节清单；画布实时显示，发布时完整再跑一次 |
| 发布版本 | 规范化 DSL、解析全部钉版本引用、生成输入输出 Schema、计算 `requiredCapabilities`（DAT-04）、计算哈希，写不可变版本，版本号按 Flow 递增；哈希与上一版相同时拒绝（没有改动）；写审计 `flow.version.publish` |
| 版本差异 | 语义差异：新增、删除、修改的节点（逐个配置字段对比）、连线变化、钉版本引用的变化；忽略布局 |
| 影响分析 | 指向该 Flow 的发布、引用 `flow.{name}` 的 Agent、引用其发布的编排规则与父 Flow、最近 7 天的调用量与成本（与 ADR-041 的影响分析一致） |
| 归档版本 | 仍被发布指向或被父 Flow 钉住时拒绝，并列出引用方 |
| 导出、导入 | 沿用 ADR-041 的导出格式；不含凭证值；导入时按名称重新解析引用，解析不到的列为待处理，生成草稿 |

### 6.3 引用的钉版本规则

| 被引用对象 | 默认 | 可选 | 理由 |
| --- | --- | --- | --- |
| 模型别名、路由计划 | 跟随 | — | 换模型是运维动作，ADR-041 |
| Prompt 模板 | 钉版本 | — | ADR-041 |
| Agent | 钉版本 | 跟随（节点上勾选，影响分析中单独列出） | Agent 决定行为；但有的团队希望 Agent 更新后 Flow 自动生效 |
| 子流程 | 钉版本 | — | 避免子流程改动绕过父流程的评测 |
| MCP Server | 钉快照 | — | ADR-023 |
| 远程节点 | 钉 Schema 快照 | — | 防止外部服务悄悄改接口 |
| 节点类型 | 钉 `typeVersion` | — | — |
| 结构化输出 Schema | 钉版本 | — | ADR-037 |
| 知识库、数据集 | 跟随 | — | 是数据，不是行为 |
| 凭证 | 跟随（只存引用） | — | 轮换不应要求重新发布 |

### 6.4 进行中的 Run

Run 创建时绑定版本 ID，整个 Run 按该版本执行；发布指针切换、回滚都不影响已经开始的 Run。Agent 节点「跟随」时，在节点开始那一刻解析 Agent 当前版本，并记入节点运行。

---

## 7. 发布（FLW-06）

### 7.1 发布形态

| 形态 | 入口 | 调用者身份 | 适用 |
| --- | --- | --- | --- |
| API | 按名称调用的 Run 接口：同步（带超时）或异步（返回 Run ID 与事件流）；查询 Run、取消 Run | API Key 的服务账号（根授予） | 业务系统集成 |
| TOOL | 注册为 ToolGateway 工具 `flow.{name}` | 发起调用的 Turn 的 Principal 与授予（ADR-029 第 47 条） | 给 Agent 用 |
| MCP | 由 MCP 出口服务暴露（CON-05） | MCP 客户端经 OAuth 授权的用户 | 外部 Agent、IDE |
| INTERNAL（新增） | 不对外；只能被编排规则、SubFlow 节点、平台服务引用 | 调用方的身份与授予 | 平台模板（如 `ontology.build.*`）、可复用片段 |

同一个版本可以同时有多个发布；名称在「空间 + 形态」内唯一（ADR-019）。Embed 聊天组件与「发布为对话应用」放到 M4 之后（FLW-17）。

### 7.2 发布的配置

| 字段 | 说明 |
| --- | --- |
| 名称、说明 | TOOL 与 MCP 形态的说明就是模型看到的工具描述，必填且有长度下限 |
| 指向版本 | 当前版本指针 |
| 输入输出 Schema | 从版本自动生成，只读 |
| 可覆盖参数 | 发布时勾选的节点配置项（如提示词变量、topK、温度），调用时可传入覆盖；其余一律以版本为准（ADR-019）。覆盖值同样受 ADR-042 的参数约束 |
| 同步超时 | API 同步模式默认 30 秒，上限 120 秒；超时后转异步返回 Run ID |
| 速率限制 | 每分钟调用次数，按发布和按调用方两级 |
| 状态 | `ACTIVE`、`PAUSED`（拒绝新调用，进行中的 Run 继续）、`RETIRED` |
| `requiredCapabilities` | 发布时计算，超出调用方授予时启动即拒（DAT-04） |

### 7.3 发布形态的限制

- TOOL 与 MCP 形态需要在工具调用的时限内返回。M3 规定：含 HumanCard、Delay 的 Flow 不能发布为 TOOL 或 MCP；含 Agent 节点的可以，但版本的估计最长执行时间（各节点超时沿最长路径相加）不得超过 10 分钟。Runtime 支持异步工具结果后再放开（FLW-18）。
- INTERNAL 形态不受此限，因为调用方（编排、SubFlow、平台服务）本身就能等待。

### 7.4 切换与回滚

- 「升级发布」把指针移到新版本，先做输入输出 Schema 兼容检查：新版本删除了字段、改了类型、新增了必填输入，都算破坏性变更。破坏性变更需要二次确认，并列出受影响的 Agent、编排规则、父 Flow。
- 回滚就是把指针移回旧版本，写审计 `flow.publication.switch`，不产生新版本号（与 ADR-023、ADR-041 一致）。
- 空间开启发布审批（ADR-025，GOV-06 在 M4）时，升级发布先进入待审批。
- 灰度（按比例分流到新版本）放到 M4 之后（FLW-19），做法与编排规则灰度相同。

---

## 8. 权限与安全（FLW-15）

### 8.1 操作与权限

沿用 M1-01 的权限点 `flow:read`、`flow:write`、`flow:publish`、`flow:run` 与资源 ACL 级别：

| 操作 | 权限点 | 资源级别 |
| --- | --- | --- |
| 查看 Flow、版本、发布 | `flow:read` | VIEW |
| 编辑草稿、试运行、单节点运行 | `flow:write` | EDIT |
| 发布版本、创建与切换发布、停用发布 | `flow:publish` | EDIT |
| 在 Studio 以外手动启动 Run（如运维重跑） | `flow:run` | USE |
| 查看他人发起的 Run 详情与节点数据 | `flow:read` | VIEW，且数据标签允许（第 8.5 节） |
| 处置 UNCERTAIN 的 Run | `flow:publish` | EDIT |
| 通过 API 调用发布 | API Key 的 `scopes` 含 `flow:run` | 服务账号对该 Flow 有 USE |

### 8.2 运行身份

| 入口 | 运行身份（`run_as`） | 授予（`grant_id`） | 配额归属 |
| --- | --- | --- | --- |
| Studio 试运行 | 编辑者本人 | 编辑者的根授予 | 编辑者 |
| API | API Key 的服务账号 | 服务账号的根授予 | 服务账号所在空间 |
| TOOL | 调用 Turn 的 Principal | 调用 Turn 的授予（透传） | 调用的 Agent 与用户 |
| MCP | OAuth 授权的用户 | 该用户的根授予 | 该用户 |
| 编排 | 规则绑定的服务账号 | 规则授权书（Mandate） | 规则（ADR-020 预算熔断） |
| SubFlow | 继承父 Run | 继承父 Run | 继承父 Run |
| 评测 | 发起评测的人 | 只读授予，工具走桩 | 发起人 |

发布者的身份不进入运行时。Flow 内每一次模型调用、工具调用、检索都以上表的身份和授予经网关检查。

### 8.3 发布时的引用检查

发布者必须对所有引用对象有 USE：Agent、子流程发布、知识库、MCP Server、Prompt 模板、凭证、远程节点。这只保证「发布者没有借 Flow 把自己用不了的东西包装出去」；运行时仍按调用方逐次检查，所以调用方没有某个知识库的权限时，Retrieve 节点照样被拒。

### 8.4 凭证与出网

- 节点配置里不允许明文凭证（沿用现有 `CredentialPolicy`），只能写凭证引用；凭证统一存放在 ADR-042 / FND-08 的 AES-GCM 加密存储中，按空间隔离。
- 凭证值不进入表达式、节点输出、日志、事件和调试面板；HTTP 响应里回显的认证头一律脱敏。
- HTTP 节点、远程节点、Notify 节点的目标主机必须在出网白名单内（FND-07 出网守卫），HTTP 节点的主机部分必须是常量，防止由输入拼出任意地址。

### 8.5 数据标签

- 每个节点输出带数据标签：取所有输入标签与该节点数据来源标签（知识库、MCP Server、数据集，ADR-040）中的最高级；节点上配置的 `labels` 只能调高不能调低。Run 的标签为全部节点的最高级。
- LLM、StructuredExtract、Agent 节点按输入标签选模（DAT 按标签选模）；对外的 HTTP、Notify、EmitEvent、API 返回按标签检查去向许可，不允许时节点失败（错误码 `DATA_LABEL_DENIED`）。
- 查看节点输入输出时同样检查标签，没有权限时只显示结构和大小。

### 8.6 审计

| 事件 | 说明 |
| --- | --- |
| `flow.version.publish`、`flow.version.archive` | 版本发布与归档 |
| `flow.publication.create`、`flow.publication.switch`、`flow.publication.pause` | 发布形态的变化 |
| `flow.run.start` | 只对 API、MCP、编排入口记录（Studio 与 TOOL 已有 Turn 或会话审计） |
| `flow.run.cancel`、`flow.run.resolve_uncertain` | 取消与 UNCERTAIN 处置 |
| `flow.debug.real_execute` | 调试时勾选「真实执行」 |

模型与工具调用的审计照常由网关记录，并带上 Run ID 与节点运行 ID。

---

## 9. 调试（FLW-13）

对标 Langflow 的 Playground，并补上企业场景需要的副作用保护。

| 能力 | 说明 | 阶段 |
| --- | --- | --- |
| 草稿试运行 | 用表单或 JSON 填输入，运行当前草稿快照（记录快照哈希以便复现）；画布实时着色，底部面板显示每个节点的输入、输出、错误、重试、耗时、token、成本 | M3 |
| 单节点运行 | 选中一个节点，用手填输入或上一次试运行的上游输出单独执行 | M3 |
| 固定输出 | 给节点固定一份输出，试运行时直接返回它，不真正执行（常用于跳过昂贵的模型调用）；画布上明显标记；不进版本 | M3 |
| 写操作保护 | 试运行中 WRITE、EXTERNAL 工具只做 `prepare`，面板显示变更单；编辑者勾选「本次真实执行」才 `commit`，仍受其本人权限与审批约束，并写审计 | M3 |
| HumanCard 调试 | 收件人默认改为编辑者本人，在面板内直接作答 | M3 |
| 从此节点重跑 | 复用某次试运行中上游节点的输出，只重跑选中节点及其下游 | M3 |
| 断点 | 在节点前设断点，试运行到此进入 WAITING，可继续、单步或终止；只对试运行生效 | M3 |
| 生产 Run 回放 | 在画布上只读回放某次生产 Run 的节点状态与数据（按第 8.5 节脱敏）；可一键「用此输入在草稿上试运行」 | M3 |
| 测试用例 | 把试运行的输入与期望输出存为评测数据集用例（EVO-02），发布时可跑批次评测（ADR-031）；有回归时阻止发布与 AST-09 同机制 | M4 |

试运行的 Run 标记为调试，保留 7 天，不计入发布统计，但用量计入编辑者配额。

---

## 10. 可观测（FLW-14）

### 10.1 Run 视图

- Run 列表：按 Flow、发布、版本、入口、状态、调用方、时间筛选；显示耗时、成本、等待中的节点。
- Run 详情：画布叠加节点状态；时间线（甘特图）显示每个节点运行的排队、执行、等待时长；点开节点看输入输出、错误、每次尝试；Agent 节点链接到该 Turn 的轨迹视图（HCI-07）；HumanCard 节点链接到审批记录；SubFlow 节点链接到子 Run。
- WAITING 与 UNCERTAIN 的 Run 在审批台有独立分组。

### 10.2 链路追踪

沿用 ADR-025：每个 Flow Run 是一条独立 trace，每个节点运行（每次尝试）是一个 span，模型调用与工具调用是节点 span 的子 span；Agent 节点的 span 链接到其 Turn 的 trace，SubFlow 链接到子 Run 的 trace，编排入口的 Run 链接回编排 Run。

### 10.3 指标

| 指标 | 维度 |
| --- | --- |
| Run 数量 | 发布、版本、入口、终态 |
| Run 耗时分布 | 发布、版本 |
| 节点耗时与失败数 | 节点类型、节点 ID、错误码 |
| 等待中的 Run 数 | 等待原因（审批、Turn、子 Run、时间） |
| 调度延迟（READY 到 RUNNING） | 实例 |
| 派发队列长度、map 在途元素数 | 实例 |

内置运行状态页展示这些指标，有 Prometheus 的环境另行导出（与 MH-10 一致）。

### 10.4 统计与成本

- 每次模型与工具调用都带 Run ID、节点运行 ID、发布 ID 与版本号，成本可以精确归到节点、Run、发布、版本。
- 统计复用 MH-11 的框架：按发布与版本给出调用量、成功率、P50/P95 耗时、成本、token、失败最多的节点；任意两个版本可以对比（ADR-041「改了什么、效果如何」）。
- 用量按第 8.2 节的身份计入配额；价值台账（ADR-035）记 `FLOW_RUN`。

### 10.5 告警

在 GOV-13 的告警规则中增加三类条件：某发布在时间窗内失败率超过阈值；Run 停在 WAITING 超过设定时长；调度延迟超过阈值。

### 10.6 保留

| 数据 | 默认保留 |
| --- | --- |
| Run 头信息 | 180 天 |
| 节点输入输出 | 30 天（空间可调）；超过后只保留结构与大小 |
| 调试 Run | 7 天 |
| 审计 | 按 GOV 审计保留策略 |

---

## 11. 与其他层的衔接

| 对象 | 方向 | 规则 |
| --- | --- | --- |
| Agent | Flow 调 Agent | Agent 节点（第 4.2 节）：新 thread、钉版本、计一层委派、授予收窄、Agent 内部的审批与自治等级（ADR-034）照常生效 |
| Agent | Agent 调 Flow | TOOL 发布 `flow.{name}`，在 AgentSpec 的工具集里引用；透传调用方授予；`requiredCapabilities` 超出授予时启动即拒 |
| Skill | — | Flow 不直接加载 Skill。需要 Skill 时用 Agent 节点，由该 Agent 的 Skill 配置决定；Script 节点（M4）与 Skill 脚本共用同一套沙箱 |
| MCP | Flow 调 MCP | MCP 节点，钉 Server 快照，结果按 Server 配置的密级打标签 |
| MCP | 外部调 Flow | MCP 发布，经 CON-05 的 OAuth |
| Knowledge | Flow 调知识库 | Retrieve 节点，经 `KnowledgeSearchApi`，按运行身份做 ACL；写入知识库的节点放到 M4 之后 |
| Model Hub | Flow 调模型 | 只经 ModelGateway；引用别名；参数覆盖是 ADR-042 第 3 层；Prompt 钉版本 |
| Orchestration | 编排调 Flow | 规则动作引用 Flow 发布（任意形态），`origin=orch`，以规则服务账号运行，幂等键为编排 Run ID；Flow 结束后把输出作为回执交回编排 Run |
| Orchestration | Flow 通知编排 | EmitEvent 节点，经 outbox，跳数 +1，`attrs` 只能来自可信来源 |
| 领域包与本体 | 平台注册节点与模板 | 平台节点经插件宿主注册；@model.build 展开为 INTERNAL 发布 `ontology.build.{defKey}@{defVersion}`（M3-03） |
| 评测 | 评测调 Flow | 被测对象可以是 Flow 发布（ADR-031），`origin=eval` |
| 审批台 | 双向 | HumanCard、工具审批、UNCERTAIN 处置都进同一个审批台，卡片上显示 Flow 名称、版本与节点 |

---

## 12. Studio 界面（FE-12〜FE-14）

沿用 ADR-028：Vue 3 + vue-flow，Studio 按路由懒加载。

| 区域 | 内容 |
| --- | --- |
| 顶栏 | 保存状态与 `revision`、校验结果数、试运行、版本、发布 |
| 左侧节点面板 | 按类别分组，可搜索；内容来自节点类型注册表（含插件、平台、远程节点，带来源标记）；已弃用的节点不显示 |
| 画布 | 拖放、连线时实时检查端口类型；节点上显示错误角标、固定输出标记、断点、运行状态；分组框与注释 |
| 右侧检查器 | 由节点配置 Schema 生成表单；表达式编辑器提供上游输出的自动补全与类型提示；显示该节点的钉版本引用 |
| 底部面板 | 试运行输入、事件流、节点数据、变更单（写操作保护）、HumanCard 作答 |
| 版本面板 | 复用 FE-07 通用版本组件：历史、语义差异、影响分析、回滚 |
| 发布面板 | 形态、名称与说明、可覆盖参数、同步超时、速率限制、破坏性变更提示 |
| Run 列表与详情 | 第 10.1 节；统计复用 FE-09 组件 |

编辑冲突：保存返回 409 时显示服务器版本与本地版本的语义差异，让用户选择覆盖或合并后再存；打开同一草稿时提示「某人正在编辑」（心跳），不做多人实时协同编辑。

M3 内置四个模板：知识库问答、文档结构化抽取、带人审的处理流程、多 Agent 流水线（以合同审查骨架为例，即 ADR-038 中固定步骤多 Agent 场景先用 Flow 骨架过渡的做法）。

---

## 13. 数据模型增量

在 ADR-019 与 M2-04、M3-02、M3-03 已定义的表结构之上增加以下字段；迁移编号在 M3 施工设计中登记，列宽与时间类型遵守 ADR-029 第 9、10、17、20、60 条（不加 `tenant_id`）。

| 表 | 新增或调整 | 说明 |
| --- | --- | --- |
| `flow_draft` | `layout_json`、`debug_json`、`based_on_version_id` | 布局与调试数据分开存放；记录草稿基于哪个版本 |
| `flow_draft_snapshot`（新表） | 草稿 ID、`revision`、DSL、保存人、时间 | 最近 20 次保存 |
| `flow_version` | `version_no`、`spec_hash`、`resolved_refs_json`、`input_schema`、`output_schema`、`required_capabilities_json`、`max_duration_estimate`、`release_note`、`status`、发布人与时间 | 钉版本引用解析结果与发布元数据 |
| `flow_publication` | `kind` 增加 `INTERNAL`；`description`、`sync_timeout_ms`、`rate_limit_json`、`paused_at`；`required_capabilities_json` 已由 M3-02 增加 | — |
| `flow_run` | `version_no`、`caller_type`、`caller_ref`（Turn、编排 Run、API Key、父节点运行）、`parent_run_id`、`parent_node_run_id`、`debug`、`draft_hash`、`idempotency_key`、`deadline_at`、`data_label`、`tokens`、`cost_micros`；`grant_id` 已由 M3-02 增加；删除 M2 专用的 `state_json` | 入口、子流程、调试、超时、标签与成本汇总 |
| `flow_node_run` | `node_type`、`type_version`、`wake_at`、`selected_ports_json`、`output_ref`、`data_label`、`tokens`、`cost_micros`、`turn_id`、`child_run_id`、`resolved_ref_json`；`parent_node_run_id`、`item_index` 已由 M3-03 增加 | 唤醒、分支选择、大载荷引用、成本、等待对象 |
| `flow_remote_node`（新表） | 名称、空间、地址、凭证引用、Schema 快照、快照版本、状态、登记人 | 远程节点登记 |

索引要点：`flow_node_run` 按（状态、`wake_at`）供唤醒器扫描，按（状态、`lease_until`）供回收器扫描；`flow_run` 按（发布、创建时间）与（空间、状态）供列表查询；`flow_run` 的（发布、`idempotency_key`）唯一。

---

## 14. 限额与默认值

| 项 | 默认 | 上限 |
| --- | --- | --- |
| 每个 Flow 的节点数 | — | 200 |
| 子图嵌套 | — | 3 |
| 子流程嵌套（展开后） | — | 5，且不得循环调用 |
| Loop 轮数 | 必填 | 1000 |
| Run 内并发节点 | 4 | 16 |
| map 并发 | 4 | 32 |
| 空间并发 Run | 50 | 空间设置 |
| 节点超时 | 同步节点 5 分钟；Agent 30 分钟；HumanCard 按配置 | 24 小时；HumanCard 30 天 |
| Run 超时 | 24 小时 | 30 天 |
| 节点重试 | 2 次 | 5 次 |
| 内联输出 | 256 KB | 单值 20 MB |
| API 输入正文 | — | 2 MB（文件走上传后传引用） |
| API 同步超时 | 30 秒 | 120 秒 |
| TOOL、MCP 形态最长执行时间 | — | 10 分钟 |

---

## 15. 工作项与估算

**原有工作项**（M3 原范围内，34 号计划此前没有人日，这里首次估算）：

| ID | 工作项 | 优先级 | 后端人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- |
| FLW-04 | Agent 节点改为等待型（WAITING + 唤醒），钉版本与委派计层 | P1 | 4 | RT-08、RT-12、FLW-09 | 节点内多步工具；重启后等待中的节点继续 |
| FLW-05 | 插件节点与远程节点 | P1 | 8 | FLW-10、ADR-026 插件宿主 | 新增节点不改内核 |
| FLW-06 | 四种发布形态、可覆盖参数、切换与回滚、破坏性变更检查 | P1 | 8 | CON-05、FLW-12 | Studio 建流 → Agent 调用；回滚后新 Run 用旧版本 |
| FLW-07 | 草稿并发保存检查与保存快照 | P2 | 1 | — | 并发保存一方得到 409 |
| FLW-08 | map 节点（已在 M3-03 估算） | P0 | 4 | FLW-09 | 见 M3-03 |
| 小计（不含 FLW-08） | | | **21** | | |

**新增工作项**（净增）：

| ID | 工作项 | 优先级 | 后端 | 前端 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| FLW-09 | 引擎重写：数据库驱动调度器、派发与租约、唤醒器、回收器、超时、取消传播、重试退避、错误路由、UNCERTAIN、大载荷外置、Run 事件流 | P0 | 10 | — | M2-04 的 FLW-03、RT-06 | ADR-030 第 4 条的调度契约测试全部通过；杀进程后 Run 正确续跑 |
| FLW-10 | DSL v1：类型化规范、端口类型系统、CEL 表达式、发布校验清单、YAML 导入导出、格式迁移器 | P0 | 6 | — | — | 第 3.7 节每项校验有用例 |
| FLW-11a | 新节点（M3）：StructuredExtract、Tool、Transform、Merge、Switch、SubFlow、EmitEvent；现有节点按新 SPI 迁移 | P0 | 6 | — | FLW-10、SCH-02 | 每个节点有执行与校验用例 |
| FLW-11b | Delay、Notify 节点 | P1 | 2 | — | FLW-09、NTF-01 | 延时节点重启后按时唤醒 |
| FLW-12 | 版本：接入统一版本框架、语义差异、钉版本解析、影响分析、导出导入 | P0 | 4 | — | GOV-12 | 回滚后差异与引用可查 |
| FLW-13a | 调试（M3）：草稿试运行、单节点运行、固定输出、写操作保护、HumanCard 调试 | P0 | 3 | — | FLW-09、TRU-05 | 试运行中写工具不 `commit` |
| FLW-13b | 调试（增强）：从此节点重跑、断点、生产 Run 回放 | P1 | 3 | — | FLW-13a | — |
| FLW-14a | 观测（M3）：节点 span、Run 列表与详情接口、成本归集到节点 | P0 | 2 | — | GOV-08 | Run 成本等于其节点成本之和 |
| FLW-14b | 观测（增强）：按版本统计、节点热点、三类告警 | P1 | 2 | — | MH-11、GOV-13 | 两个版本统计可对比 |
| FLW-15 | 权限：操作与权限点、运行身份矩阵、发布时引用检查、数据标签传播、出网检查、审计事件 | P0 | 3 | — | DAT-04、FND-07 | 第 17 节权限用例 |
| FE-12 | Studio 画布重做：Schema 驱动的节点面板与配置表单、端口类型检查、表达式编辑器、冲突处理 | P0 | — | 6 | FLW-10 | — |
| FE-12b | 表达式编辑器高级补全、模板库界面 | P2 | — | 1 | FE-12 | — |
| FE-13a | 调试面板（M3 部分） | P0 | — | 2 | FLW-13a | — |
| FE-13b | 断点、从此节点重跑、回放界面 | P1 | — | 3 | FLW-13b | — |
| FE-14a | 版本与发布面板、Run 列表与详情 | P0 | — | 2 | FE-07 | — |
| FE-14b | 统计页（复用 FE-09） | P1 | — | 2 | FE-09 | — |
| 合计 | | | **41** | **16** | | |

**M4 及以后**（不计入上表）：FLW-16 Script 节点（依赖 RT-11）、FLW-17 Embed 聊天组件与发布为对话应用、FLW-18 异步工具结果后放开 TOOL/MCP 的时长限制、FLW-19 发布灰度、节点与模板市场（并入 GOV-06）。

建议分工：FLW-09、FLW-10、FLW-12 由 BE-C（Flow 原负责人）做，FLW-08 的 BE-A 在 FLW-09 完成后开工；FLW-11a、FLW-15 可与 BE-A、BE-B 分摊。确切分工在写 M3 施工设计时定。

**关键路径提示**：M3-03 的 FLW-08（map 节点）与 ONT-06（@model.build，合同场景 P0）都依赖 FLW-09。FLW-09 必须排在 M3 前段，否则合同场景会被卡住。

---

## 16. 排期影响与方案（2026-11-06 负责人选定方案 A）

当前排期：M3 W20–W45，M4 W46–W57，总工期约 13.25 个月。原有工作项的 21 人日属于 M3 原范围，下面只看净增部分。

| 方案 | M3 做什么 | 净增（后端 / 前端） | 工期影响 | 代价 |
| --- | --- | --- | --- | --- |
| **A 全量进 M3（已选定）** | 第 15 节全部新增项 | 41 / 16 | M3 延长约 3 周为 W20–W48，M4 为 W49–W60，总工期约 14 个月 | 最完整，但再推迟一个月 |
| B 核心进 M3（原推荐） | FLW-09、FLW-10、FLW-11a、FLW-12、FLW-13a、FLW-14a、FLW-15、FE-12、FE-13a、FE-14a；其余（FLW-11b、FLW-13b、FLW-14b、FE-12b、FE-13b、FE-14b）放到 M4 之后的增强批次 | 34 / 10 | M3 延长约 2 周为 W20–W47，M4 为 W48–W59，总工期约 13.75 个月 | 断点、回放、版本统计、延时与通知节点晚到；但引擎、DSL、版本、权限、基础调试一次到位 |
| C 只补合同场景的前置 | 只做 FLW-09、FLW-10、FLW-15；Studio 仍是旧画布，没有调试与新节点；其余全部放到 M4 之后 | 19 / 0 | M3 延长约 1 周为 W20–W46，M4 为 W47–W58，总工期约 13.5 个月 | 合同场景能跑，但对标 Langflow 的构建者体验在 M3 基本没有提升 |

当时推荐 B 的理由：FLW-09 与 FLW-10 是合同场景的硬前置，无论如何都要做；版本、权限、写操作保护关系到上线安全，晚做会返工；断点、回放、统计属于增强体验，晚两三个月影响小。

**负责人于 2026-11-06 选定方案 A**：第 15 节全部新增项在 M3 完成，M3 延长约 3 周为 W20–W48，M4 顺延为 W49–W60，总工期约 14 个月。人员不变；FLW-09、FLW-10 排在 M3 前段。34 号计划与 ADR-029 第 64 条已同步更新。

---

## 17. G3 闸门增加的用例

| 用例 | 断言 |
| --- | --- |
| `G3FlowEngineGateIT#resumeAfterCrash` | Run 执行中杀掉 worker 实例，另一实例接管后 Run 成功，已完成节点不重跑 |
| `G3FlowEngineGateIT#uncertainOnWrite` | 写工具 `commit` 过程中实例崩溃，Run 进入 UNCERTAIN，不自动重试 |
| `G3FlowEngineGateIT#branchJoinSkip` | Branch + Switch + 汇合 + `when` 条件的组合按第 5.3 节执行 |
| `G3FlowEngineGateIT#cancelPropagation` | 取消 Run 后，等待中的审批被撤回，Agent 节点的 Turn 被取消 |
| `G3FlowPublishGateIT#toolGrantPassthrough` | Agent 以收窄的授予调用 `flow.x`，Flow 内的 Retrieve 被拒（DAT-04） |
| `G3FlowPublishGateIT#rollback` | 升级发布后回滚，新 Run 使用旧版本，进行中的 Run 不受影响 |
| `G3FlowPublishGateIT#breakingSchemaChange` | 新版本删除输出字段时，升级发布要求二次确认并列出引用的 Agent |
| `G3FlowDebugGateIT#writeToolPrepareOnly` | 试运行中的写工具只产生变更单，外部系统无变化 |
| `G3FlowSecurityGateIT#publishRefCheck` | 发布者没有某知识库的 USE 时发布失败 |
| `G3FlowSecurityGateIT#labelEgress` | CONFIDENTIAL 标签的数据经 HTTP 节点发往未许可的主机时节点失败 |
| `G3FlowObservabilityGateIT#costByNode` | Run 成本等于其所有节点成本之和，且能按版本查询 |

## 18. 对已有文档的修订

| 文档 | 修订 |
| --- | --- |
| ADR-019 | `continueOnError` 改为 `onError` 三选一；发布形态增加 INTERNAL；Run 状态增加 QUEUED、WAITING、UNCERTAIN、CANCELLED；节点状态增加 CANCELLED、UNCERTAIN。其余以本 ADR 为准，ADR-019 文末加修订说明 |
| 34 号计划 | FLW 表补人日与依赖，新增 FLW-09〜FLW-19、FE-12〜FE-14；M3 改为 W20–W48、M4 改为 W49–W60（方案 A） |
| ADR-029 | 新增第 64 条 |
| M3 施工设计 | 写 M3 Flow 施工设计时以本 ADR 为准，登记迁移编号 |

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 67 条。

- **第 1.1 节补充**：单个 Agent 内的多步推进方式由 ADR-046 的执行策略决定（`REACT`、`PLAN_EXECUTE`、`REFLECT`）；入口「交给哪个 Agent」由 ADR-044 的入口路由决定。Flow 中的 Agent 节点按被调用 Agent 自己的执行策略运行。
- **意图分类节点模板**：节点模板库（第 4.3 节）提供「意图分类」模板：一个 StructuredExtract 节点（输出类别、置信度、理由，Schema 预置）接一个 Switch 节点（按类别分支，低于置信阈值走 default）。这是模板，不是新节点类型，不改引擎。

## 修订：结构化输出的结局端口（2026-11-07）

见 [ADR-065](ADR-065-guardrail-pipeline-input-safety.md)〜[ADR-067](ADR-067-output-safety-guardrail-eval.md) 与 ADR-029 第 73 条：

- 声明了输出契约的结构化抽取节点与 Agent 节点有三个输出端口：`valid`（含 `VALID_WITH_UNKNOWN`）、`pending`（人工补录期间挂起）、`failed`（[ADR-066](ADR-066-structured-output-reliability.md) 第 8 节）。
- DSL 校验清单增加一条：`failed` 端口必须有连线，或节点配置「失败时终止并通知」，否则发布被拒。
- Flow 发布为 API 时，响应体固定含 `outcome`；结构化产出失败返回 HTTP 200 与 `outcome=FAILED`，500 只用于平台故障。

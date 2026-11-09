# M2 残余收口批次 B-1：外部交互台账（OBS-04a / ADR-074）实现覆盖说明

> 批次：M2 残余收口批次 B-1（规划对齐复盘后启动的 M2 缺口批次）
> 分支：`feature/m2-obs04a-ledger`（基线 main = `fae50ba`）
> 收口范围：OBS-04a 台账与出网接线部分（复盘报告缺口清单第 1 项）；im 入站/webhook 接线随 CON-02/Flow Webhook（接线点当前不存在，见遗留）
> 硬约束遵守：迁移仅 `V220__m2_ext_interaction_ledger.sql`（落 tioka-server 迁移目录）；不改 `deploy/compose.yml`；不引新运行时依赖；workspaceId 不涉及（台账为全局跨边界观测面，不带租户隔离列，与 ADR-074 字段口径一致）

## 一、ADR-074 口径 → 实现映射

| ADR-074 口径 | 实现 |
|------|------|
| 一张台账 `ext_interaction`（§1 全字段） | `V220` 全字段建表（标识/对方/身份/关联/幂等回执/内容/结果七组）+ 5 个查询索引 + `retention_class` CHECK 约束 |
| 带.subject 为 BIZ，其余 SEC（§1 保留类别） | `retention_class` 列落库（写入时按 subject 判定），纳入保留管理 |
| 纳入 ADR-072 哈希链 | `prev_hash`/`hash` 列预留（V220 注释声明）；台账哈希链封印随 `AuditChainService` 扩展另行接入，本批不启用（登记遗留） |
| `EgressClient` 是唯一出网通道，OUT 在一处写即可覆盖大部分（§2） | `DefaultEgressClient.send/sendStreaming` 统一记 OUT `http` 行：counterparty=host、endpoint=host+path（**不含查询串，密钥/参数不落台账**）、operation=HTTP method、status OK/ERROR/TIMEOUT（HttpTimeoutException 识别）、request_digest=sha256(uri+method)、大小、时延 |
| 守卫拒绝不写台账（§2：拒绝的仍按 ADR-072 记审计） | `guard.assertAllowed` 抛异常路径在台账写入之前，零台账写入（契约测试守护） |
| 台账记「跨边界发生了什么」，失败不阻断 | Recorder 与 EgressClient 双层 catch 降级：写失败仅 WARN 日志，出网主流程不受影响（契约测试 + 单测双向验证） |
| 关联键与 ADR-073 同源 | attrs 缺省时 trace_id/request_id 从 `AuditRequestContext`（MDC）兜底，与审计、traceId 贯通同一来源 |

## 二、交付物

| 交付物 | 说明 |
|--------|------|
| `V220__m2_ext_interaction_ledger.sql` | ADR-074 §1 全字段（34 列）+ 5 索引 + 保留类别/状态 CHECK；`prev_hash`/`hash` 预留 |
| `ExtInteractionWriteRow`（persistence） | 34 列落库行 record（48-CS 长参列表风格同 `AuditEventWriteRow`） |
| `ExtInteractionMapper`/XML + `ExtInteractionRepository` | 只增不改（台账 ADR 口径「台账只增」） |
| `DefaultExtInteractionRecorder`（application/obs，新增） | 主实现：attrs 提取固定列（21 个 LEDGER_KEYS）→ MDC 兜底 trace/request → 保留类别判定 → overflow 进 attrs_json；@Component 注册（Noop 未注册 bean，装配即默认生效） |
| `DefaultEgressClient`（改造） | 注入 `ObjectProvider<ExtInteractionRecorder>`（可选，缺失静默）；send/sendStreaming 计时 + 状态 + 摘要记录；双层降级保证台账永不影响出网 |
| `NoopExtInteractionRecorder` | 保留不动（API 包兜底，测试/未装配环境用） |

### 范围裁剪说明（与 ADR-074 §2 的差异，均有登记）

- `im` IN（飞书事件回调入口）：**仓库中不存在**（CON-02 入站回调在未合入分支 `feat/CON-02-inbound-callback`）——随该分支合入后接线，本批不做。
- `webhook` IN：仓库中无 Flow Webhook 发布入口——随 Flow Webhook 工作项落地。
- `im` OUT 明细（`ImChannelAdapter.send/update` 记 kind=im + external_ref）：适配器发送经 `EgressClient` 已被 `http` 行覆盖；`kind=im` 细分行（带对方回执号解析）与入站回调一同落地更有价值，避免与 http 行重复记录争议——随 CON-02 接线。

## 三、测试与验证

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `DefaultExtInteractionRecorderTest`（system-biz，mock Repository） | 7/7 | 固定列映射 + BIZ/SEC 判定 / 无 subject → SEC / MDC 兜底 / attrs 覆盖 MDC / overflow 隔离（固定列键不进 json）/ 失败吞异常 / 多条落库 |
| `DefaultEgressClientLedgerTest`（JDK 内置 HttpServer，localhost 离线） | 5/5 | OK 行（endpoint 不含查询串、digest 确定性、response_size）/ 连接失败 ERROR 行 + 异常重抛 / 守卫拒绝零台账 / recorder 缺失静默 / recorder 异常不影响出网 |
| `M2Obs04aLedgerContractTest`（server nio 源码守护，4 用例） | 4/4 | V220 全字段与哈希链列预留 / Recorder 关联键与降级 / EgressClient 接线与守卫拒绝不写 / Noop 保留 |
| 全量回归 | 见提交信息 | 全仓 `mvn test` |

## 四、遗留（登记到 M2 台账）

- **台账哈希链封印**：`prev_hash`/`hash` 已预留，`AuditChainService` 扩展封印 `ext_interaction`（ADR-074 §1「纳入 ADR-072 哈希链」），随后续批次接入。
- **im IN / webhook IN 接线**：随 CON-02 入站回调、Flow Webhook。
- **im OUT 明细行**（kind=im + external_ref 解析）：随 CON-02。
- **台账查询出口**（按 request_id/subject/counterparty/external_ref/时间段查询，ADR-074 §4）：随 OBS-05 台账查询工作项。
- **载荷保存**（payload_ref 对象存储 + 数据标签加密，ADR-074 §3）：合同场景缺省配置，随业务连接器批次。

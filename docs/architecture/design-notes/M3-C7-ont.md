# M3 批次 C-7：本体投影与规则求值（ONT-02 / ONT-04 / ONT-05）实现覆盖说明

> 批次：M3 批次 C-7（M3-03 §0/§1 V172 L105-160、§7 确认投影 L332-390、§9 rule.evaluate L437-470、§10 领域包 L471-520；ADR-036 CEL 内置函数；ADR-029 第 50/53 条）
> 分支：`feature/m3-c7-ont`（基线 main = `5bcff42`，worktree `p3-c7-ont`；主仓库未触碰，合并由 team-lead 处理）
> 收口范围：① V172 三表持久化基建（DO/Mapper/Repository 从零起）+ DraftService（revision 乐观锁）+ ConfirmIngestor（contract.model.confirmed 消费）+ DiffService；② rule.evaluate 三值语义（cel-java 0.9.1 + 表驱动 ≥24 行）；③ 领域包 SPI 四项补齐（manifest 验签 / 生产网关适配 / 类加载器隔离 / register 挂接与装配）
> 硬约束遵守：domain 包不依赖 Spring（ArchUnit 过）；本批唯一新增运行时依赖 dev.cel:cel（版本由 tioka-dependencies 管理）；不做 ONT-03/06/07、不做 REST、DefinitionService 迁 GOV-12 不做；只提交不 push

## 一、范围 → 实现映射

| 项 | 实现 |
|------|------|
| ONT-02 持久化基建 | `OntDraftDO`（@TableId instance_key）/ `OntInstanceVersionDO` / `OntValueIndexDO`（复合主键不走 @TableId，增删查走 XML；JSONB 列映射 String；`PgTextArrayTypeHandler` 自建 text[] ↔ List<String>，Connection.createArrayOf）；三 Mapper + `resources/mapper/*.xml`（`::jsonb` / `::varchar[]` 显式转型）；`OntDraftRepository`（save(row, expectedRevision) → Integer，null=乐观锁冲突）/ `OntInstanceVersionRepository` / `OntValueIndexRepository`（rebuild 先删后插） |
| ONT-02 DraftService | `save(draft)` 乐观锁（冲突抛 `DraftConcurrencyException`）、`get` / `getByStoreKey`、`propose(instanceKey)` 经 `DraftProposer` 端口弱依赖（CON-07a 未接线 → 未配置抛异常）；`ModelDraft` / `ModelDraftJson` / `OntDraftRepositoryAccess` |
| ONT-02 ConfirmIngestor | `IdempotentConsumer` 子类（consumerName=ontology-confirm，topic=tioka-biz-lease，tag=contract.model.confirmed）：①业务级幂等（已有 (instanceKey, version) → ACK）②经 `ConfirmedModelClient` 端口拉模型（CON-07a 端口）③`CanonicalJson.sha256Hex` 哈希不一致 → 抛 `ONT_HASH_MISMATCH` **拒收进死信**（ADR-029 第 50 条权威口径：重试耗尽由 IdempotentDeliveryService 写死信）④SchemaRegistry 弱依赖校验 `{defKey.collectionKey}@{defVersion}` ⑤单事务写 CONFIRMED + 旧版 SUPERSEDED + diff_prev / diff_draft + ont_value_index 重建（topics 从 DefinitionService.pathCatalog 按**结构键**桥接，`structuralKeyOf` 去选择器）+ outbox `tioka.ontology.instance_confirmed.v1` |
| ONT-02 DiffService | 委托 domain 层 `ModelPathDiff`（纯逻辑）：路径级 ADD/REMOVE/CHANGE；数组按 `x-tioka-identity` / `type` 身份对齐，顺序变化不算差异，无身份按下标；ValueCell 整 cell 比较 |
| ONT-04 rule.evaluate | ontology-api：`RuleSpec`（ruleId/applies/requires/check/onBlank/params）、`RuleResult`（conclusion 四值/touched/params/error/needsReview）；`RuleEvaluator`：cel-java 0.9.1 同一运行时、编译缓存 ruleId+sha256(expression)、50ms evalPool 超时隔离（ADR-029 第 53 条记缺失）、applies→requires→check 三值流程（未知→缺失 / 空白→onBlank / 不适用→不适用 / 待复核按已知值参与计算+needsReview）；`ModelValueProvider`（model_json → CEL 变量 m：known 叶子→标量、unknown/blank/NA→optional.none、数组→identity 键 map、Integer→Long 归一）；`PlatformCelFunctions`（ADR-036 L168 清单：isKnown/status/months/segments/sum/annualRate）；touched 由 AST 静态提取（m 链 + 全部前缀） |
| ONT-05 四项补齐 | ① `PackSignatureVerifier`（SHA256withRSA + X.509 公钥 HEX/PEM 可配，签名覆盖清单规范串+入口类字节，规范串与 FND-12 `PluginSignatureVerifier` 一致；**缺省 required=false**，偏差登记）；② `PluginHostPackGateway` 适配 infra `DefaultPluginHost`（status→outcome）；③ `IsolatedPackClassLoader`（parent=null + SPI 白名单直通 + ontology-api 经宿主加载器委派保类身份 + 其余 com.tioka.. 拒绝加载 + JDK 经 bootstrap 委派）；④ `DomainPackLoader` 重写（权限→验签→gateway.enable→隔离实例化→manifest.schemas[] 按 `{key}@{version}.json` 命名注册 SchemaRegistry→register：definition→saveDraft、validators/folding/projections/celFunctions 登记）；`CelFunction` 扩展 record(name, description, implementation)；`RuleEvaluator` 挂接 pack 函数 |
| 装配 | `OntologyInstanceAutoConfiguration`（@MapperScan + DraftService/DiffService/ConfirmIngestor beans）+ `OntologyPackAutoConfiguration`（`tioka.ontology.pack.*` 开关缺省关闭；PluginHost @ConditionalOnBean 网关、缺省 localPackGateway 降级）；AutoConfiguration.imports 追加两行 |

## 二、测试清单（surefire 实证数字）

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `RuleEvaluatorTest`（biz，新增） | 5/5 | 表驱动 26 行（applies 假 / requires 未知·不存在·不适用·空白 / 已知满足·不满足 / check 访问未知·空白→缺失 / 待复核 needsReview 4 例 / 表达式错误·编译错误→缺失 / sum·months·isKnown 双向·annualRate）/ touched 提取（requires+check 链前缀、ValueCell value/status/sources）/ 超时→cel_timeout / pack 函数成员调用满足·不满足 / 编译缓存按 ruleId+表达式 |
| `ConfirmIngestorIT`（biz，新增） | 7/7 | 重复投递幂等 ACK（零副作用）/ 哈希不一致拒收 ONT_HASH_MISMATCH / Schema 校验失败拒收 ONT_SCHEMA_INVALID / 拉取失败 ONT_MODEL_FETCH_FAILED / payload 缺字段 / **确认全流程**（CONFIRMED 行字段、diff_prev·diff_draft 内容、supersedeOthers、值索引路径·状态·topics 桥接、outbox instance_confirmed payload）/ structuralKey 去选择器 |
| `DraftServiceTest`（biz，新增） | 7/7 | 首次保存 revision=1 / 更新按已有 revision / 冲突抛 DraftConcurrencyException / get 往返 JSON 解析 / propose 未装配·草稿不存在 / propose 成功写回 bizDraftRef / getByStoreKey |
| `DiffServiceTest`（biz，新增） | 1/1 | 与 domain ModelPathDiff 输出一致 |
| `ModelPathResolverTest`（biz，新增） | 7/7 | 选择器（['RENT@S1']/[DEPOSIT]/[0]/[*]）、unquote、非法路径、状态归一与聚合（REVIEW 传递）、NA 存储缩写、isValueCell |
| `ValueIndexWalkerTest`（biz，新增） | 4/4 | 路径+状态产出、身份/下标路径、NA 缩写 ≤VARCHAR(12)、空模型 |
| `ModelPathDiffTest`（biz，新增） | 6/6 | 增删改、身份对齐顺序无差异、元素变化与增删、无身份数组下标、ValueCell 状态变化、标量 |
| `PackSignatureVerifierTest`（biz，新增） | 10/10 | 正确签名、乱序权限规范串、篡改清单/字节拒收、非签名密钥、多公钥任一命中、缺签名/缺公钥/非法编码、缺省关闭、PEM 公钥、非法公钥构造 |
| `IsolatedPackClassLoaderTest`（biz，新增） | 4/4 | SPI 白名单与宿主同一 Class、ontology-api 与宿主同一 Class、内部类拒绝（framework/biz）、JDK 可用且未知类报错 |
| `DomainPackLoaderIT`（biz，扩充，`-Dtest` 显式） | 5/5 | 既有 networkDenied / reload + 新增：**signatureMismatch 拒载且不交宿主** / **运行时编译 LeasePack 入口类的端到端 jar 加载**（隔离类加载器实例化、packFunctions、schema 按 {key}@{version}.json 注册 + validate 双向、definition 落 DefinitionService 草稿）/ **register 挂接**（validator/folding/projection/celFunctions 访问器） |
| ontology 模块全量（surefire 默认，`-am` 联动） | **111/111 BUILD SUCCESS** | ontology-biz 22 类：本批新增 9 类 51 用例（RuleEvaluator 5 / ConfirmIngestorIT 7 / DraftService 7 / DiffService 1 / PathResolver 7 / ValueIndexWalker 4 / PathDiff 6 / PackSignature 10 / IsolatedLoader 4）+ DomainPackLoaderIT 扩充 3（共 5）+ 既有 12 类 55（def 10 / doc 25 / schema 20）；ontology-api 无测试 |
| runtime 模块抽样回归（`-am` 联动） | **131/131 BUILD SUCCESS** | framework PersistenceProperties 变更无回归 |
| connector 模块抽样回归（`-am` 联动） | **49/49 BUILD SUCCESS** | 同上 |
| tioka-server 架构契约八套（`-Dtest` 显式） | **59/59** | PersistenceArch 6（ont 三表 exempt 登记过）/ TiokaArchitecture 11 / M3Architecture 10 / M2Architecture 5 / ModuleDependencyArch 5 / RuntimeApiContract 9 / M2B4VaultContract 6 / M3C1aMigrationContract 7 |

## 三、实测登记（坑与修复）

1. **cel-java 0.9.1 同一 overloadId 多绑定仅首个生效（first-wins，探针实证）**：isKnown/status 按 7 个参数类逐类注册绑定后，运行期只有第一个（Optional）可分派——`内置isKnown_已知为真` 报 No matching overload。修复：isKnown/status/sum/annualRate 改**单 Object.class 绑定兜底**（canHandle 对 `Object.isAssignableFrom(实际参数类)` 恒真，Map/Long/Optional 等全形态进同一实现，实现内判型）。Sum/annualRate 需 Object 的另一原因：m 里的数组是 identity 键 map（非 List），字面量 list 才是 ImmutableListValue。
2. **pack 函数成员调用编译期缺 member 重载**：`x.f()` 报 `found no matching overload for 'freeMonths' applied to 'dyn.()'`；须 `CelOverloadDecl.newMemberOverload` 与全局重载并存（运行期成员调用仍按全局 1 参走同一 overloadId 分派）。
3. **checked AST 形态与解析态不同**：`m.feeItems['RENT@S1']` 的索引节点在 checked AST 中是 `target 缺失、args=[base, key]` 的两参全局调用（探针 dump 实证）；且 `childrenOf` 不遍历 SELECT 节点导致 touched 链在 `.abate` 截断。两处修正后 touched 提取正确。
4. **Integer 运行期比较分派失败**（探针实证）：cfg/模型侧整数统一归一 Long（ModelValueProvider.scalarOf / normalizeCfg）。
5. **隔离类加载器两次踩坑**：①parent=null 时覆写 loadClass 不得直接 findClass——JDK 类须经 `super.loadClass`（parent=null → bootstrap 委派），否则 java.util.HashMap 都加载不到；②ontology-api 类不能由隔离器从 URL 加载副本——同一 api 类会出现两个 Class（instanceof 失效、NoClassDefFound），改为 `com.tioka.ontology.api.` 前缀经宿主类加载器委派（与宿主同一 Class，白名单 SPI 直通同口径），api codeSource URL 随之不再必需。
6. **删除探针类后 target 残留 stale class/report**：CelProbeTest/EvalDbg/EvalDbg2 源文件已删但 target 旧产物导致全量首跑出现假失败；清理 target 后重跑确认真实全绿（流程坑，非代码缺陷）。
7. **路径选择器引号**：`['RENT@S1']` 解析须 unquote（ModelPathResolver.parse/step），否则 requires 全部 UNKNOWN→MISSING。

## 四、规格落地决策（按任务书冻结决策登记）

1. **V172 无 workspace_id 列**：brief 与 DDL 实际不符——三表为实例级投影（instance_key/store_key 承载门店维度），走 `tioka.persistence.workspace-scope-exempt-tables` 登记（framework `PersistenceProperties` 默认清单已追加 ont_draft / ont_instance_version / ont_value_index），PersistenceArchTest 过。
2. **实例 model_json 约定**：数组元素以 `x-tioka-identity`（或 `type`）身份寻址；值叶子统一 ValueCell `{value, status, sources}`（status ∈ explicit/derived/default/inherited/known/review/blank/not_applicable/na）。DiffService / ValueIndexWalker / ModelValueProvider 同一约定。
3. **值索引状态存储口径**：`ValueStatus.stored()` → KNOWN/UNKNOWN/BLANK/NA/REVIEW（NA 缩写 ≤ VARCHAR(12)）。
4. **CEL m 暴露语义**：known（含 review）叶子→普通标量可直接参与运算；unknown/blank/NA→`optional.none()`（比较/运算运行期报错→RuleEvaluator 错误路径记缺失，check 结果为 none→缺失）；数组→identity 键 map。
5. **Schema 校验口径**：按确认模型对象类型集合可得部分校验（`{defKey}.{collectionKey}@{defVersion}` 命中才校验）；def 持久化不在本批（DefinitionService 迁 GOV-12 冻结）。
6. **ConfirmedModelClient / DraftProposer 为端口**：生产适配器随 CON-07a 业务连接器接线（批次文档登记）。
7. **领域包函数以 DYN 全局+member 重载暴露**：`(dyn)→dyn` 单重载（optional(dyn) 与 dyn 双重载编译期判重叠，探针实证）；运行期 Object 兜底绑定。

## 五、偏差登记

1. **manifest.signature 验签缺省关闭**：M3-03 §10 原文未指定算法与公钥下发流程——按批次冻结决策采用 SHA256withRSA + 可配置公钥（X.509 HEX/PEM），`tioka.ontology.pack.signature-required` 缺省 false，与 FND-12 宿主验签双轨并存；公钥下发流程落地后由配置打开。
2. **死信口径实证**：ADR-029 第 50 条「不一致拒收进死信」——ConfirmIngestor 哈希不一致抛 IllegalStateException，IdempotentDeliveryService 异常路径重试→末次写死信（B-6b 批次已实证该分支）；离线 IT 以 EventPublisher 接口桩覆盖拒收分支（哈希不一致 / Schema 无效 / 拉取失败三例均不落库不发布）。
3. **ConfirmedModelClient / DraftProposer 生产适配器未接线**：端口已定义并装配弱依赖，CON-07a 批次补齐。
4. **TableId/@TableField 与 XML 分工**：ont_instance_version / ont_value_index 复合主键不走 @TableId，insert/findByKey/supersedeOthers/insertBatch 走 mapper XML（`::jsonb`、`::varchar[]` 显式转型）。
5. **RuleEvaluator.touched 为静态 AST 提取**：仅覆盖 m 链（IDENT/SELECT/CALL `_[_]`/CONSTANT），comprehension 内链前缀同样收集；cfg 侧引用不记 touched（口径：touched=实例路径）。

## 六、验收对照

| 验收项（任务书） | 状态 |
|------|------|
| V172 三表持久化 + DraftService 乐观锁 + ConfirmIngestor 幂等/拉模型/哈希拒收/Schema 校验/单事务写/outbox | ✅（ConfirmIngestorIT 7/7） |
| 哈希不一致拒收进死信（ADR-029 第 50） | ✅（异常路径 + 拒收分支离线实证） |
| DiffService 路径级差异 + x-tioka-identity 对齐 | ✅（ModelPathDiffTest 6/6 + DiffServiceTest） |
| RuleEvaluator 三值语义 + 50ms 超时 + 编译缓存 + touched | ✅（RuleEvaluatorTest 5/5，表驱动 26 行 ≥24） |
| ADR-036 L168 平台内置函数全量 | ✅（isKnown/status/months/segments/sum/annualRate，表驱动覆盖） |
| 领域包验签（缺省关闭+登记偏差）/ 生产网关适配 / 类加载器隔离 / register 挂接 | ✅（PackSignatureVerifierTest 10/10 / PluginHostPackGateway / IsolatedPackClassLoaderTest 4/4 / DomainPackLoaderIT 5/5） |
| domain 包不依赖 Spring / cel-java 唯一新增依赖 | ✅（ArchUnit 过；pom 仅 dev.cel:cel） |

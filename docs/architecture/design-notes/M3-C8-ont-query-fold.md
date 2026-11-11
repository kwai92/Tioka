# M3 批次 C-8：本体三查询工具与折叠调度（ONT-03 / ONT-07）实现覆盖说明

> 批次：M3 批次 C-8（M3-03 §8 读模型工具 L471-480、§12 折叠调度 L520-528；ADR-036、ADR-040 行谓词口径；ADR-029 第 50/53 条）
> 分支：`feat/m3-c8-ont-query-fold`（基线 main = `d89bf1e`，worktree `Tioka-wt-c8`；主仓库未触碰，不 merge 不 push）
> 收口范围：① ONT-03 三查询工具（@entity.read / @model.query / @model.coverage）+ ModelQueryService ACL 行过滤 + CoverageService 四态统计 + ontology.instance 数据集登记；② ONT-07 折叠调度（V173 ont_state + FoldScheduler 幂等消费 + Redis 互斥锁 + 投影写回 + state_folded 事件）
> 硬约束遵守：application/query、application/fold 不直持 Mapper（PersistenceArchTest 过）；无新增 `new ObjectMapper`（TiokaArchitectureTest.noNewObjectMapper 过）；ont_state 无 workspace_id 列已登记 workspace-scope-exempt；ontology.domain 包未触碰；只提交不 push

## 一、范围 → 实现映射

| 项 | 实现 |
|------|------|
| ModelQueryService（ONT-03） | `readConfirmed(instanceKey, version?, principal)`：version 缺省 `findLatestConfirmed`，显式 version 只回 CONFIRMED 行；行谓词 = `acl_principals ∩ 主体标识 ≠ ∅`（主体标识 = userId ∪ onBehalfOf ∪ roles，经 on_behalf_of 交集的有效身份面；ACL 为空 deny-by-default 不可见）。交集为空 → empty（NOT_FOUND 口径，不泄露实例存在性，区别于 DatasetGateway「数据集不存在」404）。`readDraft`：需额外权限 `ontology.read_draft`（无权限同口径 NOT_FOUND） |
| CoverageService（ONT-03） | 输入 = ont_value_index（按 topics 统计）+ `ModelDefinition.Topic` 期望的对象类型（按 `m.<collectionKey>` 路径前缀匹配）与陈述类型（按 `[<typeKey>]` 身份选择器匹配）+ 主题 CEL 适用条件（经 `RuleEvaluator` 复用同一 CEL 运行时，applies=false → NOT_APPLICABLE，不重写 CEL 解析）。状态映射（冻结口径）：KNOWN→已知；BLANK→空白；UNKNOWN/REVIEW→未知（REVIEW 归未知）；NA→触发「不适用」。四态聚合：不适用 > 无（全部未知）> 空白（存在空白 / 部分缺失）> 有；返回缺失期望项清单 |
| 三 ToolProvider | `EntityReadToolProvider`（entity.read：实例内容 + version="draft" 草稿读）/ `ModelQueryToolProvider`（model.query：值索引按 topic/status/pathPrefix/limit 过滤）/ `ModelCoverageToolProvider`（model.coverage：走 CoverageService）。同构先例 connector `DataQueryToolProvider`：`name()="ontology"`、三参 invoke 报错须带 ToolCallContext、五参 invoke 带主体；`ToolDescriptor.builder()` 设 `reversibility(NONE)`（READ risk，动作类别 ontology.read 由授权书 toolPatterns 归类，与 DocToolProvider 同口径）；ToolResult 带 dataLabel = 实例行 data_label（草稿沿 V172 缺省 CONFIDENTIAL） |
| ontology.instance 登记 | `OntologyInstanceDatasetRegistrar`：向 DatasetStore best-effort 注册 Registration（datasetName=ontology.instance、provider=ontology、RowRule 谓词描述 acl 交集口径、mode=ENFORCE、policyVersion=1）。**登记偏差**：DatasetStoreDb.save 为管理面 API（M3 只读，save 抛 UnsupportedOperationException）→ 捕获后 WARN 跳过；查询执行二选一走「ModelQueryService 内行过滤」（内存交集谓词，非 CEL），DatasetPolicyCompiler 未复用亦未重写 |
| V173 迁移 | `ont_state` 七列：store_key PK / pack_key / input_hash（注释：输入版本集合 {instanceKey:version} 排序后哈希）/ state_json JSONB / review_items JSONB / folded_at / written_back DEFAULT false；副本同步 `docs/contracts/db/`；`PersistenceProperties` exempt 清单同处追加 `ont_state`（V172 三表同处惯例），PersistenceArchTest 过；M3C1aMigrationContractTest 无穷举清单无需补（V151/V156/V222 专项不变） |
| 持久化 | `OntStateDO`（extends TechnicalRecordDO，@TableId store_key，JSONB 列映射 String 同 repo 惯例）+ `OntStateMapper`（@InterceptorIgnore tenantLine）+ XML（upsert `ON CONFLICT (store_key) DO UPDATE`、findByStoreKey、markWrittenBack）+ `OntStateRepository`；@MapperScan 在 `OntologyFoldAutoConfiguration` 重复声明（与 instance 装配独立） |
| FoldScheduler（ONT-07） | extends `IdempotentConsumer<Map>`（consumerName=ontology-fold、topic=tioka-ontology、tag=tioka.ontology.instance_confirmed.v1）：①payload 不含 storeKey → instanceKey 回查（find 按 versionId / findLatestConfirmed），查不到记日志 ACK（幂等吸收不进死信）②`listConfirmedByStore` 全量确认版③input_hash = {instanceKey:version} 键值对按 key 排序 \n 拼接 SHA-256 hex（乱序同哈希），与 ont_state 相同 → 跳过④Redis 锁 `ont:fold:{storeKey}`（照 RedisThreadLock 模板：setIfAbsent + compare-and-DEL Lua，TTL 60s 不续约），拿不到抛 `ONT_FOLD_LOCK_BUSY` 重试；锁 try-finally 释放，锁内二次去重⑤FoldingEngine 经 DomainPackLoader（触发实例 defKey/defVersion → PublishedDefinition.packKey/packVersion）取 Optional；为空 → 记日志 ACK 不写 ont_state（降级口径冻结）⑥PureFunctionExecutor.run 包裹 fold（30s 超时）⑦upsert ont_state（written_back=false）⑧依次 `ProjectionHook.project`（经 executor 包裹），全部成功 → written_back=true⑨outbox 发 `tioka.ontology.state_folded.v1`（EventPublisher，MANDATORY 事务由投递适配器提供，ConfirmIngestor 同路） |
| state_folded payload | required=[instanceId, stateVersion]：instanceId=触发实例键、stateVersion=inputHash（状态版本标识，登记偏差）；补 storeKey/inputHash/writtenBack（additionalProperties=true 允许；粒度偏差已在台账登记） |
| 装配 | `OntologyQueryAutoConfiguration`（tioka.ontology.query.enabled 缺省关闭：两 Service + 三 Provider + PrincipalAccessor + Registrar）与 `OntologyFoldAutoConfiguration`（tioka.ontology.fold.enabled 缺省关闭：OntStateRepository + FoldLock（@ConditionalOnBean StringRedisTemplate）+ PureFunctionExecutor 兜底 + FoldScheduler）；imports 文件追加两行 |

## 二、测试清单（surefire XML 实证数字）

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `ModelQueryIT`（biz，Testcontainers PG 16，V172+V173 直建 DDL，独立容器隔离） | 3/3 | **aclFiltered**（acl 含经办人 → 可见且 dataLabel=CONFIDENTIAL；不含 → NOT_FOUND；不存在实例同口径）/ **draftRequiresReadDraftPermission**（无 ontology.read_draft → NOT_FOUND；有 → DRAFT 内容）/ **modelQueryAndCoverageFollowAcl**（model.query、model.coverage 同 NOT_FOUND 口径 + dataLabel） |
| `CoverageServiceTest`（biz，单元） | 5/5 | 四态各一（有 / 无 / 空白 / 不适用）+ REVIEW 归未知缺失项断言 + 部分未知→空白且缺失项只列未知项 |
| `FoldSchedulerIT`（biz，Testcontainers PG 16，独立容器 + 测试域包 TestDomainPack） | 4/4 | **orderIndependent**（同一组确认事件按 3 种顺序投递到 3 个门店，state_json 解析树完全相同、contractCount=3/totalBaseAmount=6000；投影写回 written_back=true；state_folded payload 字段断言）/ **duplicateDeliveryDoesNotRefold**（重复投递 fold 计数不变）/ **missingEngineDegraded**（无 DomainPackLoader → ACK 不写 ont_state 不发事件）/ **unknownInstanceAbsorbed**（触发实例查不到 → ACK 幂等吸收） |
| ontology-biz 模块全量（surefire 默认，IT 排除） | **104/104 BUILD SUCCESS** | 新增 CoverageServiceTest 5 + 既有 99 全绿 |
| 三新测试类显式（`-Dtest=ModelQueryIT+CoverageServiceTest+FoldSchedulerIT`） | **12/12** | 逐类读 surefire XML：FoldSchedulerIT 4/0/0、CoverageServiceTest 5/0/0、ModelQueryIT 3/0/0 |
| tioka-framework 全量 | **25/25** | PersistenceProperties exempt 追加无回归 |
| tioka-server 全量 | **108/108 BUILD SUCCESS** | 全量回归 |
| 架构契约八套（`-Dtest` 显式） | **59/59** | PersistenceArch 6（ont_state exempt 过）/ TiokaArchitecture 11（noNewObjectMapper 过）/ M3Architecture 10 / M2Architecture 5 / ModuleDependencyArch 5（ontology-biz → connector-biz 无禁令）/ RuntimeApiContract 9 / M2B4VaultContract 6 / M3C1aMigrationContract 7 |

## 三、实测登记（坑与修复）

1. **PostgreSQL jsonb 文本规范化**：state_json 经 `::jsonb` 入库后文本带空格（`{"contractCount": 3}`），子串断言 `contains("\"contractCount\":3")` 假失败——IT 断言改为 `M.readTree(stateJson)` 解析树比较（与存储格式无关）。
2. **BaseMapper.insert 对 JSONB 列缺 ::jsonb 转型**：OntDraftRepository.insert 走 MyBatis-Plus BaseMapper.insert 时 `model_json`（JSONB）报 type mismatch——既有生产代码经由 XML `::jsonb` 显式转型的路径不受影响（ont_draft 的 insert 为 C-7 既有潜在坑，本批 IT 用 SQL 直插绕开，未改既有文件，登记待办）。
3. **测试共享 static publisher mock**：FoldSchedulerIT 多用例共享 EventPublisher mock，`verify(never())` 被前序用例交互击穿——每用例入口 `Mockito.reset(publisher)`。

## 四、规格落地决策（按任务书冻结决策登记）

1. **主体标识口径**：`principalKeys = userId ∪ onBehalfOf ∪ roles`（经 on_behalf_of 交集的有效身份面；ACL 命中任一标识即可见）。ACL 为空数组 → 不可见（deny-by-default）。
2. **input_hash 算法**：`{instanceKey:version}` 键值对 key 排序后以 `\n` 拼接 UTF-8 SHA-256 hex；排序保证乱序同哈希。
3. **review_items 提取**：FoldingEngine C-7 简化签名只返回 stateMap——约定 stateMap 可含 `reviewItems` 键（List），折叠时提取进 review_items 列，state_json 存完整返回 map。
4. **投影钩子入参**：`ProjectionHook.project(Map)` 传折叠后 stateMap（M3-03 §12「把状态写回业务系统」）；任一钩子失败 → written_back 保持 false 并 WARN（不回滚 ont_state）。
5. **stateVersion = inputHash**：state_folded 契约 required 字段无现成对应，取 inputHash 作为状态版本标识（粒度偏差已在台账登记，按任务书补 storeKey/inputHash/writtenBack）。
6. **锁拿不到 → 抛 `ONT_FOLD_LOCK_BUSY`**：让消息重试（不同门店并行、同门店串行）；60s 租约不续约，过期自动失效 + input_hash 幂等兜底（决策已冻结）。
7. **主题 CEL 适用条件源缺省为空**：ModelDefinition.Topic 无条件字段（冻结 record），CoverageService 以 `Function<String,String>` 注入条件源，装配缺省 `topic -> null`；M3-04 领域包复核时挂接真实来源（登记偏差）。条件求值复用 RuleEvaluator（合成 RuleSpec applies=条件、check="true"），不重写 CEL 解析。

## 五、偏差登记

1. **ontology.instance 数据集登记延后至管理面开放（registrar 已移除）**：初版 OntologyInstanceDatasetRegistrar 向 DatasetStore best-effort 注册，但 DatasetStoreDb.save 是管理面 API（M3 只读，save 抛 UnsupportedOperationException，运行时零实际作用），且 ontology-biz → connector-biz 依赖违反 LayeredDependencyArchTest.bizLayersMustNotDependOnForeignModuleLayers——CI 回归修复中删除 registrar 与 connector-biz 依赖；查询行过滤在 ModelQueryService 内存实现（任务书二选一口径二），DatasetPolicyCompiler 未复用亦未重写 CEL；治理面登记随管理面批次开放后补。
2. **ontology-biz 新增两个运行时依赖**：`tioka-module-connector-biz`（DatasetStore/DatasetPolicyModel 登记，无 Maven 环：connector-biz 不依赖 ontology）、`spring-boot-starter-data-redis`（RedisFoldLock）。测试依赖：testcontainers junit-jupiter/postgresql + postgresql 驱动（BOM 管理）。
3. **OntDraftRepository.insert JSONB 转型潜在坑**（既有代码，见三-2）：DraftService 保存路径若在真实 PG 上执行 BaseMapper.insert 会报错，建议后续批次为其补 XML insertDraft（本批未触碰既有文件）。
4. **RedisFoldLock 落 infrastructure 包**（FoldLock 接口在 application/fold）：锁实现依赖 StringRedisTemplate，避免 application 层依赖具体中间件。
5. **FoldScheduler 的 packHooks/packKeyOf 重复解析 def**：fold 与 projectAll 各自经 DefinitionService 解析 packKey——保持 DomainPack SPI 签名不动（C-7 冻结），牺牲一次内存查找。

## 六、验收对照

| 验收项（任务书） | 状态 |
|------|------|
| ModelQueryIT#aclFiltered：无门店权限经办人读不含其 acl 的实例 → NOT_FOUND；读前者成功；draft 无 ontology.read_draft → NOT_FOUND | ✅（3/3） |
| CoverageServiceTest 四态各一 + 缺失期望项断言 | ✅（5/5） |
| FoldSchedulerIT#orderIndependent：3 种顺序投递 state_json 完全相同（input_hash 去重） | ✅（4/4，含去重 / 降级 / 写回 / 吸收） |
| V173 单表七列 + input_hash 注释 + exempt 登记 + 契约副本 | ✅（PersistenceArch 6/6、M3C1a 7/7） |
| 三 ToolProvider：reversibility(NONE)、ontology.read、dataLabel、NOT_FOUND 口径一致 | ✅ |
| tioka.ontology.query.enabled / tioka.ontology.fold.enabled 缺省关闭 + imports 登记 | ✅ |
| 折叠幂等（input_hash 去重 + 锁内二次去重）/ 无引擎降级 ACK / outbox MANDATORY 惯例 | ✅ |
| 新增测试全真实通过（无 @Disabled）；逐类读 surefire XML | ✅（12/12 新增 + 回归 237 全绿） |

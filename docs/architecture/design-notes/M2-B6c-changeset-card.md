# M2 残余收口批次 B-6c：TRU-06b 变更单卡片后端（PK-25）

> 批次：M2 残余收口批次 B-6c（生产代码 + 测试 + docs）
> 分支：`feature/m2-b6c-changeset-card`（基线 main = `01b1e04`）
> 收口范围：M2-00 第 81 行 TRU-06b「变更单卡片后端（飞书审批卡片、审批台详情）」；M2-06 PK-25
> 迁移：**零迁移**（复用 `runtime_approval.card_json` JSON 列内嵌 changeSet、`trust_action.approval_id` 现有列回链；V222 号段未被占用，批次文档登记 M4 起号顺延）

## 一、实现内容（对照 M2-05 第 43、97–104 行与 PK-25）

| 能力 | 实现 | 文件 |
|------|------|------|
| 渠道无关变更单组件 ChangeTable（M2-05 第 97 行） | `ImMessage` 增加 `ChangeTable`/`Row` record（渠道无关），无既有构造调用方，签名变更零破坏 | `connector-api/.../im/ImMessage.java` |
| FeishuCardBuilder 变更单分支（PK-25 指定） | `connector-biz/.../application/FeishuCardBuilder`：card_json map → `ImMessage`（`changeSetCardFromJsonMap`）；`ImMessage` → 飞书卡片 JSON（标题=changeSet.summary；IRREVERSIBLE 红/其余蓝模板；可撤销/可冲销/不可撤销标签；estimated 加「推断」；表格「对象/字段/原值/新值」前 10 条；合计记录数/金额按 currency；批准/拒绝/在 Tioka 中查看全部按钮——>10 条或 estimated 时） | `connector-biz/.../application/FeishuCardBuilder.java` |
| card_json 带 changeSet/reversibility/estimated（M2-05 第 43 行） | `ChangeSetCardRenderer`（runtime-biz application）：trust_action 行 + `ChangeSet` → 渠道无关 card_json（kind=changeSet、changeStatus、reversibility、estimated、changeSet、changeTable 行、changeTotals、viewAll）；UPDATE 只列变化字段；DELETE 展示 before、CREATE/SEND/PAY 展示 after；>10 条或 estimated 置 viewAll | `runtime-biz/.../application/changeset/ChangeSetCardRenderer.java` |
| 变更单卡片审批入口 + 状态机挂钩 | `ChangeSetCardService#openCardApproval`：对 REQUIRE_APPROVAL 的 PROPOSED prepared call 写 `runtime_approval`（kind=card、policy=changeSet、card_json=渲染结果）并经 `TrustActionRepository#linkApproval` 回链 `trust_action.approval_id`（UPDATE 带 `approval_id IS NULL` 守卫，幂等复用）；发出 `approval_requested` 事件（复用现有信封契约，未改） | `runtime-biz/.../application/changeset/ChangeSetCardService.java` |
| 审批台详情查询（HCI-05b） | `ApprovalQueryApi#changeSet(approvalId)` → `ChangeSetDetail`（approvalId/preparedId/toolName/changeStatus/reversibility/estimated/rows/changeSet）；实现带与 `get()` 同口径的工作区隔离（跨空间按不存在处理）；REST：`GET /api/runtime/approvals/{id}/changeset` | `runtime-api/.../ApprovalQueryApi.java`、`runtime-biz/.../kernel/query/ApprovalQueryService.java`、`runtime-biz/.../kernel/api/ApprovalController.java` |
| trust_action 查询扩展 | `findByApprovalId` / `linkApproval`（Mapper + XML + Repository，application 不直持 Mapper） | `runtime-biz/.../infrastructure/TrustActionRepository.java`、`infrastructure/mapper/TrustActionMapper.java`、`resources/mapper/TrustActionMapper.xml` |

## 二、状态机口径（M2-05 第 111 行 / ADR-033）

- PROPOSED：`DefaultToolGateway#prepare` → `PreparedCallStore.insertProposed`（本批不动）；挂起审批时 `openCardApproval` 回链 approval_id。
- APPROVED：审批通过经 `PreparedCallStore#markApproved`（PROPOSED → APPROVED，乐观状态守卫）。
- APPLIED：`DefaultToolGateway#commit` → `markApplied`（带 ApplyReceipt 补偿令牌，幂等 commit 返回缓存结果）。
- STALE：commit 过期（valid_until）或漂移检测（指纹重算不一致）→ `markStale`。
- FAILED：工具执行异常 → `markFailed`。
- estimated：预估态（非事务型写工具 `ChangeSetFactory#estimatedFromArgs`），与状态正交，卡片加「推断」标签并强制「查看全部」。

## 三、测试与验证记录

| 测试 | 位置 | 运行归属 | 覆盖点 | 结果 |
|------|------|----------|--------|------|
| `FeishuCardBuilderTest` | connector-biz test（默认 surefire） | 离线（纯 JUnit5） | 12 条变化 → 10 行 + 查看全部；IRREVERSIBLE 红模板；estimated 加「推断」；合计金额格式化；四态快照；card_json map 往返；无 ChangeTable 保持原卡片形状 | **6/6 绿** |
| `ChangeSetCardIT`（重写） | tioka-server test（`*IT` 默认排除，`-Dtest` 承接） | 离线部分 = 纯 JUnit5 + Mockito，无容器 | 四种状态快照（card_json 关键字段断言）；UPDATE 只列变化字段 + 10 行上限 + viewAll；飞书侧渲染快照（标题/标签/合计/新值）；审批详情查询（`detailByApproval`/`detail`/空态）；openCardApproval 写 card_json 带 changeSet + linkApproval 回链 + 幂等复用 + 事件；PreparedCallStore 状态机（APPLIED/STALE/APPROVED，ApplyReceipt 补偿令牌） | **10/10 绿** |
| `ChangeSetCardIT$ContainerRequired`（嵌套骨架） | 同上 | 需真实容器（PG/Testcontainers + Redis），@Disabled 注明保留，随 CI failsafe 承接 | 全链 prepare → openCardApproval → commit（approval_id 落库、REST `GET /api/runtime/approvals/{id}/changeset`、漂移 STALE） | 0 跑 / 骨架保留 |

命令记录：
- `mvn -pl tioka-modules/tioka-module-connector/tioka-module-connector-biz -am test -Dtest=FeishuCardBuilderTest -Dsurefire.failIfNoSpecifiedTests=false` → 6/6 绿
- `mvn -pl tioka-server -am test -Dtest=ChangeSetCardIT -Dsurefire.failIfNoSpecifiedTests=false` → 10/10 绿（跳过 0；嵌套 Disabled 容器骨架不计）
- `mvn -pl <改动模块> -am test` 与 `mvn -pl tioka-server -am test -Dtest='PersistenceArchTest,TiokaArchitectureTest,M2ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false` → 无新违例（见提交信息）

## 四、硬约束遵守

- 零迁移：未新增/修改 `db/migration`（复用 card_json JSON 列与 `trust_action.approval_id`，V144 已建列）；V222 号段留待「先合者占号」，M4 起号顺延。
- 不改 `deploy/compose.yml`；不引新依赖；MQ 信封契约不动（approval_requested 复用现有 payload 组织方式）；权威隔离字段 workspaceId，无 tenantId。
- ArchUnit：application 只持 Repository（`TrustActionRepository`/`ApprovalRepository`），业务 ID `Ids.newId("apv")`。
- 未触碰 orchestration-biz / infra-biz / `docs/contracts/audit/actions.yaml`；未修改 `docs/architecture/construction/M2-00-M2实施计划.md`（回写由主理人统一处理）。

## 五、未尽事项（登记）

1. **Turn 执行循环未接 prepare/commit**：M2-05 第 28 行「TurnExecutor 写工具走 prepare → 判定 → commit」在生产循环尚未接线（B-5 已实证），故 `openCardApproval` 暂无生产调用方（服务入口 + REST/测试就绪）；接线时挂审批应改调本服务（或 ApprovalSuspendService 增补 changeSet 分支）。
2. **审批卡片 IM 推送**：`approval_requested` → IM 卡片推送链路未接（connector 侧无消费者组装 ImMessage）；`FeishuCardBuilder#changeSetCardFromJsonMap` + `build` 已就绪，接线时按 `runtime_approval.card_json` 组装即可。
3. **审批决定 → commit 续跑**：approval_decided → `gateway.commit(preparedId)` 的续跑路径未接（M2-05 第 48 行），TRU-05/M1-02 现有续跑机制不变。
4. **容器全链用例**：`ChangeSetCardIT$ContainerRequired` 骨架保留，待 CI 容器环境补跑。
5. **审计动作**：本批未新增审计动作（tool.invoke 既有动作覆盖 prepare/commit 侧审计）；如需 change_set 专项动作，登记待统一处理（不动 actions.yaml）。

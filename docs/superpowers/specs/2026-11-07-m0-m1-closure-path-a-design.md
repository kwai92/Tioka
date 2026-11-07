# M0/M1 路径 A 收口设计

> 日期：2026-11-07  
> 状态：**已完成**（Path A 实现落地于 worktree `fix/m0-m1-closure-path-a`；待授权提交/PR）  
> 依据：`M1-00` 第 3/12 节；`M0-01` ENG-11；ADR-029 第 10/20/60 条；ADR-072（OBS-01/02）；34 号计划 OBS 节；调研 09  
> 选定方案：路径 A — 最小闭环后再开 M2

---

## 1. 问题与目标

### 问题

M0/M1 **实施计划完整**，PR #8 / #24 已把主功能链合入 main，本地 G0/G1 闸门绿。但相对计划验收仍有偏差：

1. **M0 前置 P2 未落地**：JVM/JDBC UTC 未配置；无 `TimeZoneIT`；`RuntimeTurnDO` 未继承 `MutableRecordDO` 且使用 `OffsetDateTime`（违反 DO 时间约定）。
2. **GOV-02 原验收未达成**：审计 `trace_id` 空；`AuditCoverageIT` 仍有大量「待各域补」白名单；工具审计失败只打日志；登录 IP 可经 `X-Forwarded-For` 伪造。
3. **M1-00 §12 关闭门禁未齐**：夜间真实模型无连续成功证据；界面演示走查无人签记录。

事后登记的 **OBS-01/02** 即对第 2 点的显式工作项，属 M1 收口，不改 M1 周次叙事。

### 目标

在开 M2 前，诚实关闭：

- M0 地基约定（时区 + Mutable DO）
- GOV-02 / OBS-01 / OBS-02
- M1-00 §12 可核验部分（夜间至少可跑通；演示走查留痕）

### 非目标

- 不开 M2 功能（outbox、飞书、编排派工等）
- 不做 depth-p0 / PoC 保真计划
- 不实施 CON-04、ENG-07（仍按 `M1-00` 顺延 M2 W14–W15）
- 不实施 OBS-03 及以后（M2+）
- 不做完整 GOV-09 哈希链 / 封印归档（M4）；本波次仅处理违规物理删的**最小安全行为**
- 原则上不改前端（除非审计字段展示阻塞验收）

---

## 2. 波次与成功标准

| 波次 | 工作项 | 成功标准 |
| --- | --- | --- |
| **W0** | M0-P2 时区与 DO | `TimeZoneIT` 绿；生产/本地/CI/JDBC 固定 UTC；`RuntimeTurnDO`（及同裁定的 Mutable 表 DO）对齐基类与 `LocalDateTime`（语义 UTC）；相关 Runtime IT 绿 |
| **W1** | OBS-01 | 新写入审计的 `trace_id`、`request_id` 非空；IP 仅经可信代理规则解析；`AuditedAspect` 捕获非 `BizException` 亦记 ERROR；工具审计失败写入降级文件并指标/日志级别符合 ADR-072（禁止只 `warn` 后丢弃） |
| **W2** | OBS-02 | `AuditCoverageIT` 白名单**仅**四类：`PUBLIC_AUTH`、`READ_ONLY_POST`、`NOT_IMPLEMENTED`、`INBOUND_LEDGER`；每条标注类别与原因；原「待各域补」清零 |
| **W3** | 关闭门禁 | 仓库机密 `TIOKA_REAL_MODEL_*` 配置后夜间 workflow 至少 1 次成功（争取连续 3 天）；按 M1-00 §12.5 演示链路走查并留短记录 |
| **W4** | 宣告 | 文档注明 M1 收口完成；遗留仅 CON-04/ENG-07 → M2；可开 M2 |

---

## 3. 设计决定（已锁定）

### 3.1 时区与 DO（W0）

- JVM：`-Duser.timezone=UTC`（Dockerfile、CI、本地启动脚本/文档一致）。
- JDBC URL：加 `options=-c%20TimeZone=UTC`（与 `M0-01` ENG-11 一致）。
- DO：禁止 `OffsetDateTime`；有状态机、需更新但不软删的表走 `MutableRecordDO`；`RuntimeTurnDO` 按此收口（映射层用 UTC `LocalDateTime` ↔ 领域 `Instant`）。
- 补 `TimeZoneIT`：断言 JVM 默认时区与会话 `TimeZone` 为 UTC。

### 3.2 审计上下文与失败语义（W1 / OBS-01）

- 写入端（切面、`AuditService`、工具网关、认证/出网拒绝等）**自动补齐** `trace_id`、`request_id`；缺则生成并放入 MDC/请求上下文，禁止调用方漏填导致空列。
- 客户端 IP：仅信任配置的代理跳数 / 头；否则用 `remoteAddr`；伪造 `X-Forwarded-For` 不生效（对齐 34 号 OBS-01 验收）。
- `AuditedAspect`：业务成功同事务；`BizException` 与其它异常均记审计（DENIED/ERROR）；再失败走降级文件。
- `DefaultToolGateway.auditInvoke`：失败不得只 `log.warn`；按 ADR-072 写本地降级文件（只追加）并暴露可观测信号（至少日志 ERROR + 可检索路径/计数）。

### 3.3 覆盖清零（W2 / OBS-02）

- 白名单合法四类见 ADR-072 §5；「待补」非法。
- 写接口优先 `@Audited`；领域服务手写审计须在覆盖测试登记。
- 动作名遵循现有/ADR-072 登记制方向；本波次不强制建成完整动作目录服务（OBS-05 属 M2），但新增 action 字符串须稳定、可检索。

### 3.4 `purgeExpired` 物理删（本波次最小安全）

- **决定**：停用或改为 no-op，并打明确日志/配置开关默认关闭物理 `DELETE`；禁止继续按「已转发且超保留期」物理删除 `audit_event`。
- 完整封印 → 归档 → 删分区仍属 **GOV-09 / OBS-10（M4）**，本波次不实现。

### 3.5 夜间与演示（W3）

- Workflow 已存在：`.github/workflows/nightly-real-model.yml`。
- 负责人配置 `TIOKA_REAL_MODEL_BASE_URL` / `API_KEY` / `MODEL`；至少验证 1 次绿；§12.3「连续 3 天」作为关闭争取目标，若排期紧可在宣告中注明「已通、连跑观察中」。
- 演示：企业账号登录 → 选已发布 Agent → 多步 MCP → 另一标签停止 → 管理后台见用量与审计；结果写入短 md 或 PR 描述清单。

---

## 4. 约束

- 无 `tenantId` / `tenant_id`；`*-biz` 不依赖其它 `*-biz`；ORM 仅 MyBatis-Plus。
- 分层：`application` → `infrastructure.Repository` → `mapper`。
- G0/G1 全程保持绿；回归优先于新债修复中的重构。
- 破坏性 DO/列类型变更须有迁移或兼容读写策略，并在 PR 说明演练结果。

---

## 5. 验证

- `mvn test`；`mvn verify -Pgate`（G0+G1）。
- 新增/加严：`TimeZoneIT`；OBS-01 上下文与 IP、降级文件 IT；`AuditCoverageIT` 白名单结构断言（拒绝「待补」类注释/条目）。
- 人工：夜间 run 链接；演示走查清单。

---

## 6. 交付与 PR 切分建议

1. `fix/m0-p2-timezone-do` — W0  
2. `feat/OBS-01-audit-context` — W1 + purge 最小安全  
3. `feat/OBS-02-audit-coverage` — W2  
4. 文档/走查 + 夜间配置说明 — W3/W4（可附在 OBS-02 PR 或独立 docs PR）

实施计划另文：`docs/superpowers/plans/2026-11-07-m0-m1-closure-path-a.md`（spec 审阅通过后撰写）。

---

## 7. 遗留（明确不在本波次）

| 项 | 去向 |
| --- | --- |
| CON-04、ENG-07 | M2 W14–W15 |
| OBS-03〜OBS-12、GOV-09 全量 | M2〜M4（见 34 号 / ADR-072） |
| depth-p0-poc-fidelity | 独立计划，不阻塞本收口 |

---

## 8. Spec 自检

- [x] 无 TBD/占位实现步骤冒充设计  
- [x] 与 ADR-072 四类豁免、OBS-01/02 人日定位一致  
- [x] `purgeExpired` 范围已锁定为最小安全，非 GOV-09 全量  
- [x] 非目标列出 M2/depth-p0/CON-04  
- [x] 成功标准可验证  

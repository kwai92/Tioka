# Design: 编码规约与统一名词（system 试点）

- 日期：2026-11-07
- 状态：Accepted（试点实施）
- 试点模块：`tioka-module-system`

## 1. 决策

| 项 | 定案 |
| --- | --- |
| 范围策略 | 名词释义 + Cursor/人读规约 + 代码修缮三条线同做；先试点 system，再推广 |
| 阿里规约落地 | 文档 + `.cursor/rules`；**本阶段不引入** CheckStyle / P3C Maven 插件 |
| 改名力度 | **允许破坏性对齐**：角色码、公开契约字段一并改，附 Flyway / 配置 / 测试迁移 |
| 平台管理员角色码 | `tenant_admin` → **`system_admin`**（修订 ADR-029 第 60 条「保留历史角色码」） |
| AuditEvent | 删除占位字段 `tenantId` |
| 明确排除 | 不重命名 `tioka-framework.TenantContext`（Depth P0 X-04 冻结；登记遗留） |

## 2. 权威文档

| 文档 | 作用 |
| --- | --- |
| [47-GL-统一名词释义](../../architecture/enterprise/47-GL-统一名词释义.md) | 产品/工程名词与禁用同义词 |
| [48-CS-编码规约摘要](../../architecture/enterprise/48-CS-编码规约摘要.md) | 人读版阿里规约摘录 + Tioka 分层约定 |
| `.cursor/rules/tioka-glossary.mdc` | Agent 始终加载的名词硬约束 |
| `.cursor/rules/tioka-alibaba-java.mdc` | Java 文件打开时的编码约束 |

冲突时：更新后的 ADR-029 / 本 spec / 47-GL 优先于旧 ADR-015 正文中的「租户」表述。

## 3. 试点修缮清单

1. ADR-029 第 60 条：角色码改为 `system_admin`；OIDC `tenant-role` → `system-role`
2. Flyway `V136__rename_tenant_admin_to_system_admin.sql`：存量 `role_code` / `sys_role.code` 从 `tenant_admin` 迁到 `system_admin`（与 main 的 `V127__m1_audit_request_id` 撞号后改用 M2 段预留号；V128/V129 不变）
3. system 模块：字符串、方法名、OidcProperties、AuditEvent、DO 包统一（`dataobject` → `persistence`）
4. 跨模块硬编码与 IT 同步替换（否则验收无法绿）
5. system 内明显魔法数字抽常量（不顺手大重构）

## 4. 验收标准

1. 47-GL、48-CS、两份 Cursor rules 就位；架构 rule 引用 47-GL
2. ADR-029 第 60 条与代码角色码均为 `system_admin`
3. 生产/测试 **Java/YML** 源码无残留 `"tenant_admin"`；`AuditEvent` 无 `tenantId`
4. system 无 `infrastructure.dataobject` 包
5. 相关 IT 可通过（BootstrapAdmin / WorkspaceMember / JwtResourceServer 等）

> 历史 Flyway（V100/V103/V110）可保留创建时的旧码；由新迁移脚本在运行时改写。`rg` 断言以 `*.java` / 应用 `*.yml` 为准。

## 5. 后续推广（本试点不实施）

- 按模块复制「名词子集 + 违规清单 + 修缮」到 runtime / agent 等
- 评估 ArchUnit 命名/分层规则或 P3C（下一里程碑）
- 择机拆除 `TenantContext`（独立变更，避开 fidelity P0）

# Tioka Code Wiki

> 面向研发的代码级 Wiki：解释 Tioka 仓库的**整体架构、模块职责、关键类与函数、依赖关系、运行方式**。
>
> 本 Wiki 与 `docs/architecture`（设计/规范/ADR/实施）互补：`docs/architecture` 回答「要做什么、为什么」；本 Wiki 回答「代码在哪里、怎么组织、怎么跑」。

## 适用范围与基线

- 仓库：`f:\AI时代\Tioka`（`com.tioka` / `revision 0.1.0-SNAPSHOT`）
- 形态：Java 17 + Spring Boot 3.2 多模块 Maven 单体仓库 + Vue 3 前端
- 模块数：15 个业务模块（`tioka-modules` 下），另有 `tioka-framework`、`tioka-server`、`tioka-dependencies`、`tioka-testkit`、`tioka-pack-lease`、`tioka-ui`
- 数据库迁移：Flyway，最高版本 `V227`

## 文档目录

| 文档 | 内容 |
|---|---|
| [01-项目概览.md](./01-项目概览.md) | 项目定位、能力地图、技术栈、工程规模、术语表 |
| [02-整体架构.md](./02-整体架构.md) | 分层架构、运行时主链路、横切机制、部署形态 |
| [03-模块职责与依赖.md](./03-模块职责与依赖.md) | 15 个模块职责、api/biz 划分、SPI、依赖矩阵与禁止项 |
| [04-关键类与函数.md](./04-关键类与函数.md) | 按模块梳理关键类、关键方法与文件路径 |
| [05-数据模型与事件契约.md](./05-数据模型与事件契约.md) | Flyway 迁移、数据域、MQ 主题、事件与 API 契约 |
| [06-运行与部署.md](./06-运行与部署.md) | 环境要求、中间件、配置 profile、启动/构建/脚本 |
| [07-测试与质量门禁.md](./07-测试与质量门禁.md) | 测试分层、gate profile、G1–G7 门禁、CI 流水线 |

## 快速导航（按目标）

- 想跑起来 → [06-运行与部署.md](./06-运行与部署.md)
- 想读懂 Runtime 执行内核 → [03](./03-模块职责与依赖.md) + [04](./04-关键类与函数.md) 的 runtime 节
- 想加一个模块/接口 → [02-整体架构.md](./02-整体架构.md) 的依赖规则 + [03](./03-模块职责与依赖.md) 的禁止项
- 想接入新事件 → [05-数据模型与事件契约.md](./05-数据模型与事件契约.md)
- 想加测试/过门禁 → [07-测试与质量门禁.md](./07-测试与质量门禁.md)

## 相关既有文档（权威来源）

- 体系总览与边界：`docs/architecture/systems/S0-体系总览与边界.md`
- 模块依赖：`docs/architecture/module-dependencies.md`
- Java 工程架构：`docs/architecture/enterprise/44-EA-Java工程架构.md`
- 统一名词释义：`docs/architecture/enterprise/47-GL-统一名词释义.md`
- 决策记录（ADR-008 ~ ADR-075）：`docs/architecture/adr/`
- 实施计划：`docs/architecture/implementation/`、`docs/architecture/construction/`

> 维护说明：当模块/包结构、SPI、MQ 主题或启动方式变化时，请同步更新对应章节。文中代码引用使用 `file:///` 绝对链接，可直接跳转。

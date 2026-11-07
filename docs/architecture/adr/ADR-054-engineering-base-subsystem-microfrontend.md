# ADR-054 — Java 工程化底座、子系统授权模式与微前端

- 状态：Accepted（设计定案，待实现；在 M4 之后的 F 阶段实施）
- 日期：2026-11-06
- 覆盖工作项：BAS-23〜28、BAS-FE-10〜13
- 详细设计：[S15](../systems/S15-Foundation基础平台系统.md) 第 5、6、19 节
- 相关：[ADR-051](ADR-051-foundation-platform.md)、[ADR-052](ADR-052-org-account-authz-center.md)、[ADR-028](ADR-028-hci-frontend.md)、ADR-029 第 60、69 条、`module-dependencies.md`
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

## 背景

负责人明确：Foundation 应当既是 Java 工程化底座，也是通用企业中台，包括组织架构、用户权限、子系统与菜单与授权模式、模块与功能级微前端。ADR-051〜053 只覆盖了中台服务，没有底座、子系统目录、授权模式与微前端。

## 决定

1. **工程化底座**是一组按语义化版本发布的构件：`foundation-dependencies`、`foundation-parent`、按能力拆分的 starter（core、web、security、data、mq、cache、observability、audit、client）、`foundation-test`（Testcontainers 基座与 ArchUnit 规则集），加上工程模板与 CI 模板。通用部分从 `tioka-dependencies`、`tioka-framework` 抽出；Tioka 之后以底座为父工程，`tioka-framework` 只留空间与归属过滤等 Tioka 特有部分。不做代码生成器。
2. **目录层级**为子系统 → 模块 → 功能 → 操作，由子系统的权限清单声明；支持按子系统、模块、功能的功能开关，停用后由快照与 starter 统一生效。
3. **授权模式**按子系统四选一：集中、分级（子系统管理员）、混合（Foundation 管功能权限，子系统管资源级权限，Tioka 用此模式）、自管（只用身份、组织与单点登录）。另支持组织分级管理员，不能授予高于自身的权限。
4. **微前端**：门户为基座，推荐无界（wujie）加载 Vue 3 + Vite 子应用；菜单项声明加载方式（内置路由、子应用、iframe、外链）；登录在基座完成，菜单只来自授权快照；基座与子应用只经前端包定义的通信接口交互；不强制共享框架实例，公共组件以 npm 包发布；子应用可独立运行，加载失败时降级为新窗口打开。Tioka 的管理与构建页作为子应用挂入门户，对话界面保留独立入口。
5. **排期**：负责人选定方案 C，全部在 M4 之后的 F 阶段实施（S15 第 19.1 节）；M3、M4 期间按 S15 第 19.2 节的过渡约定行事，降低迁移成本。

## 备选

| 备选 | 不选原因 |
| --- | --- |
| 只写工程规范，各项目复制代码 | 很快漂移，升级无法统一 |
| 代码生成器 | 生成代码无法随底座升级；团队已用 Cursor |
| qiankun | 对 Vite 子应用需额外改造，ESM 支持不好 |
| Module Federation 作子系统集成 | 隔离弱，要求构建工具与版本对齐；可用于同团队的组件共享 |
| 纯 iframe | 路由同步、弹窗、保活与通信体验差；保留为第三方页面的加载方式 |
| 只支持集中授权 | Tioka 的资源级权限、外购系统无法纳入 |

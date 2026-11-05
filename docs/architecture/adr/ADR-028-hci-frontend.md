# ADR-028 — HCI 前端架构与各工作台

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：HCI-03〜08
- 对应设计：设计 N
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 HCI-03〜08。核心决定是：**前端只认一种流协议（Runtime 事件 + item 类型），所有工作台复用同一套渲染组件**，不再按页面各写一套流解析。

## 前端架构

| 项 | 定案 |
| --- | --- |
| 框架 | 保留现有 Vue 3 + TypeScript + Vite + Element Plus + Pinia（vue-flow 画布），单一应用按路由分区：/chat、/studio、/console、/admin（ADR-029 第 26 条更正） |
| 接口类型 | 后端 OpenAPI 生成 TS 客户端（CI 校验生成物无差异），禁止手写请求类型 |
| 状态 | 服务端数据用 TanStack Query（`@tanstack/vue-query`）；流状态用每个 thread 一个 Pinia store，由事件归并得出 |
| 鉴权 | OIDC 授权码 + PKCE 由后端完成（ADR-029 第 32 条）；access token 只在内存，刷新令牌放 HttpOnly Cookie |
| 权限显隐 | 登录后拉取 `/me/permissions`，菜单与按钮按权限码隐藏；后端仍做最终校验 |
| 国际化 | 中英双语，文案统一放 i18n 资源 |

## 流协议（与设计 B 一致）

前端通过 SSE 订阅 `/threads/{id}/events`，断线用 Last-Event-ID 续传。事件只有 `turn.*`、`item.*`、`approval.*` 三族；item 类型固定为 message、reasoning、tool\_call、tool\_result、file\_ref、citation、card、compaction、child\_thread、error。新增类型必须先改契约（事件 schema），前端遇到未知类型显示通用占位，不报错。对外的 AG-UI 兼容是在网关做协议转换，不影响内部协议。

## 共享组件

- **ItemRenderer**：按 item 类型渲染，所有工作台共用。
- **ApprovalCard**：工具审批与卡片表单（kind=tool/card），支持批准、拒绝、修改参数后批准。
- **TrajectoryView**：按 Turn 展开 item 时间线，显示模型、token、耗时、引用来源，可钻入子 thread。
- **CitationPanel**：知识引用的原文片段与文档跳转（按用户 ACL 再校验）。

## 各工作台

| 工作台 | 主要页面 | 依赖后端 |
| --- | --- | --- |
| 对话（HCI-03） | 会话列表、分支、文件上传、Agent 选择、记忆管理、分享 | 设计 B、H |
| 审批中心（HCI-04） | 待我审批、我发起的、按 Agent 与风险筛选、批量处理 | runtime\_approval |
| 轨迹与调试（HCI-05） | Turn 时间线、重放、对比两次运行、导出 | runtime\_item、model\_usage |
| Studio（HCI-06） | Agent 编辑与试运行、Skill 管理、Flow 画布、路由方案、发布与评测 | 设计 E、I、J |
| 知识控制台（HCI-07） | 知识库、数据源同步、文档状态、检索调试、评测集 | 设计 D |
| 管理控制台（HCI-08） | 成员与组、角色、资源授权、模型与配额、审计查询、渠道接入、编排规则 | 设计 A、C、F、G、K、L |

## 质量约束

- 每个工作台有 Playwright E2E，跑在真实后端（门禁 G2 起）。
- 首屏 JS 不超过 300KB（gzip），Studio 与 Flow 画布按路由懒加载。
- 可访问性：键盘可操作，审批与表单符合 WCAG AA 对比度。
- 现有按页面手写的 SSE 解析和 mock 数据在 HCI-03 完成后删除。


## 修订（2026-11-05）

- 前端技术栈为 Vue 3，不是 React（ADR-029 第 26 条）。
- 工作项编号以 34 号计划为准：HCI-03 管理后台、HCI-04 渲染快照、HCI-05 审批台、HCI-06 AG-UI 映射、HCI-07 轨迹视图、HCI-08 工作台与产物中心；上表「各工作台」只作功能划分（ADR-029 第 27 条）。

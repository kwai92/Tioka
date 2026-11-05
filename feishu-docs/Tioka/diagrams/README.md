# Tioka 架构图形库（draw.io）

> **可编辑源**：本目录 `*.drawio`（diagrams.net / draw.io 打开）  
> **文档内嵌**：各 Markdown 含同义 Mermaid，便于飞书 push 阅读  
> **索引**：见 [DIAGRAM-INDEX.md](DIAGRAM-INDEX.md)

## 视觉体系（v2）

| 角色 | 形态 | 配色 |
| --- | --- | --- |
| 控制面 / 平台 | 圆角矩形 + 蓝渐变 + 阴影 | `#F0F7FF → #93C5FD` |
| 执行 / 产品 | 圆角矩形 + 绿渐变 | `#ECFDF5 → #6EE7B7` |
| 外部系统 | **六边形** + 琥珀渐变 | `#FFF7ED → #FDBA74` |
| 人 / 审批 | **椭圆** | 淡紫（克制使用） |
| 数据存储 | **圆柱** | 石板灰 |
| 决策点 | **菱形** | 琥珀 |
| 画布 | 浅灰虚线底衬 + 深色标题条 | — |

避免紫光霓虹堆砌；主色为石板蓝 / 松绿 / 琥珀。

## 约定

| 项 | 规则 |
| --- | --- |
| 命名 | `{topic}.drawio`，如 `ta-c4-context.drawio` |
| 修改 | 优先改 `scripts/gen-drawio.mjs` 后重跑生成 + `embed-diagrams.mjs` |
| 导出 PNG | draw.io「文件 → 导出为 → PNG」（勾选阴影）放入 `exports/` |

## 打开方式

- 桌面 App：Draw.io / diagrams.net  
- VS Code：Draw.io Integration 扩展  
- 浏览器：https://app.diagrams.net → Open Existing Diagram  

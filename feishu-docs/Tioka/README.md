# Tioka 文档（本地源）

飞书知识库是发布面；**以本目录 Markdown 为源**，改完后 `push`。

## 目录层级（与飞书同构）

```
Tioka/
├── 00-项目主页.md                 # 地图 + 阅读顺序
├── 01-管理文档/                   # 需求 / 总览 / 接口 / ADR / 运维
├── 02-企业架构/                   # BA TA DA AA EA FE + 架构评审
├── 03-能力域详设/
│   ├── 总览与基座/                # S0 S8 S9
│   ├── 执行与编排/                # S1 S2
│   ├── 产品与交互/                # S3 S4 S5 S7
│   ├── 连接器/                    # S6
│   └── 模型与资产控制面/          # S10–S14
├── 04-实施交付/                   # 方案 / W0–W5 计划 / 实施评审
└── project.json                   # 飞书节点映射（勿手改）
```

## 常用命令

```bash
S=~/.cursor/skills/feishu-docs/scripts/feishu.mjs
node $S ensure Tioka    # 补齐分组并搬迁节点到正确层级
node $S push Tioka      # 推送正文
node $S status Tioka
```

## 权威关系

| 类型 | 权威 |
| --- | --- |
| 产品范围 / 门禁 | S0 + 需求 + ADR-009 |
| 企业架构基线 | 40–45（ADR-013） |
| 域内详设 | 对应 S 篇 |
| 执行任务 | 31 checklist |

> **镜像声明（X3 裁决，2026-11-09）**：本目录为 `docs/architecture/**` 的发布面手工镜像，**不参与代码↔设计一致性校验基线**；行数与文件集与权威源存在已知差异（见 `docs/architecture/ops/doc-single-source.md` X4 说明）。

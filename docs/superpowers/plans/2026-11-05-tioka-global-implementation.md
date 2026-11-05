# Tioka 全局实施计划（入口）

> For agentic workers: 执行权威为仓库内任务 checklist；详设以 S0–S14 为准。

**Goal:** 按 W0–W5 交付完整企业级 Agent 套件，W1 为商业 PoC Gate。

**权威文档（本地 / 飞书同源）：**

| 文档 | 路径 |
| --- | --- |
| 全局实施方案 | [docs/architecture/implementation/30-全局实施方案.md](../architecture/implementation/30-全局实施方案.md) |
| W0–W5 任务计划 | [docs/architecture/implementation/31-W0至W5实施计划.md](../architecture/implementation/31-W0至W5实施计划.md) |
| 多轮评审记录 | [docs/architecture/implementation/32-多轮评审记录.md](../architecture/implementation/32-多轮评审记录.md) |
| 飞书源 | `feishu-docs/Tioka/30|31|32-*.md` |

**Architecture:** 自研 Runtime；Model Hub S10；资产控制面 S11–S14；Foundation S9。

**Tech Stack:** Java 17+ / Spring Boot 3.2.x / 单体先跑 / PG+Redis+MQ（W0 ADR 收口）。

实施时请打开 **31** 按波次勾选任务；评审结论见 **32**。

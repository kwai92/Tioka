#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 1 · 系统上下文图（G4 系统设计）

场景识别：系统架构图（System Context）。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案）。
本图为 Tioka 既有代码的实证事实映射，不展开内部组件（内部组件归图 2 模块图）。
组件清单：角色 5 类 / 上游设计基线 / 外部依赖 E-01 至 E-06 / 下游消费者 / 中心系统单节点。
连接关系：角色「使用」、外部系统「双向调用 / 数据推送 / 订阅」、下游「逐字注入消费」。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 940
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 系统设计 · 图 1 系统上下文图",
           "实证事实映射（G4）：中心为单一系统节点，周围为角色 / 外部依赖 / 上下游；边标注关系语义；P0 四条链路以橙色标注")

# ── 角色带（上方） ──────────────────────────────────
c.band(82, 118, "角色（§2.3 用例图角色 / G3 §2.1 五类干系人）",
       "使用 / 审阅 / 运维 / 触达；证据 D2 §40-BA 5.1、D17 §3、G3 §2.1", "#eff6ff", "#2563eb", "5 类角色")
roles = [
    ("甲方决策者：平台与架构负责人", "审阅四态判定 / 签署 Gate"),
    ("最终用户 A：业务方与产品", "Chat / FlowStudio / Approval"),
    ("最终用户 B：开发与测试", "契约核对 / 切片交付"),
    ("受影响方：终端用户", "发消息 / 观察 SSE delta"),
    ("受影响方：运维与交付方 SRE", "compose 五服务 / 监控告警"),
]
for i, (t, s) in enumerate(roles):
    c.node(60 + i * 258, 82 + 62, 240, 50, t, s, "#dbeafe", "#3b82f6", tsize=11, ssize=9.2)

# ── 中心系统（单一节点） ─────────────────────────────
c.band(232, 300, "Tioka 系统（本轮中心节点，不展开内部组件）",
       "企业级 Agent 套件；15 个业务域 api 与 biz 双子模块；私有化本地 compose 五服务", "#f0fdfa", "#0d9488", "系统边界")
c.rect(360, 300 + 66, 680, 210, "#ffffff", "#0f766e", rx=12, sw=2.2)
c.text(700, 300 + 100, "Tioka 代码与设计一致性校验及后续链路规划", size=17, color="#0f766e", weight="bold", anchor="middle")
c.lines(400, 300 + 128, [
    "接入：tioka-ui（Vue 3）/ tioka-server 启动面 / SSE events / Webhook",
    "应用：15 业务域（runtime / orchestration / chat / model / agent / mcp / skill /",
    "        knowledge / flow / connector / ontology / system / infra / governance / eval）",
    "底座：tioka-framework（11 包）+ 契约与迁移 + compose 五服务",
], size=12, color="#334155", lh=22)
# P0 落点
c.rect(400, 300 + 222, 600, 40, "#fff7ed", "#ea580c", rx=8, sw=1.2)
c.text(700, 300 + 247, "P0 首批四条链路：L1 状态落库与会话锁 · L2 假实现下线 · L3 MQ 闭环核验 · L4 SSE 续传与审批恢复",
       size=11.5, color="#9a3412", weight="bold", anchor="middle")

# ── 左侧外部依赖 ────────────────────────────────────
c.band(232, 300, "外部依赖（E-xx）", "", "#fef2f2", "#dc2626", "E-01 ~ E-06", x=40, w=300)
ext = [
    ("E-01 Keycloak 24.0", "OIDC 认证 / 双向调用"),
    ("E-02 飞书开放平台与 IM", "入站消息 / 异步推送"),
    ("E-03 OpenAI 兼容 Provider", "HTTPS / SSE / 默认 mock"),
    ("E-04 MCP over HTTP", "工具协议 / 双向调用"),
    ("E-05 业务系统与知识源", "经 Connector 适配 / 双向"),
    ("E-06 docs/architecture 设计基线", "上游 / 文件读取 / 唯一权威"),
]
for i, (t, s) in enumerate(ext):
    c.node(52, 232 + 62 + i * 40, 276, 34, t, s, "#fee2e2", "#ef4444", tsize=10.5, ssize=8.6)

# 右侧下游消费者
c.band(232, 300, "下游消费者（逐字注入）", "", "#f5f3ff", "#7c3aed", "G5 / G6", x=1060, w=300)
downs = [
    ("G4 UserStory", "product-story-designer 消费边界"),
    ("G5 部署设计", "platform-architect 消费边界"),
    ("G5 安全设计", "security-architect 消费边界"),
    ("G6 一致性矩阵", "主理人汇总四态与冲突裁决"),
]
for i, (t, s) in enumerate(downs):
    c.node(1072, 232 + 62 + i * 52, 276, 44, t, s, "#ede9fe", "#8b5cf6", tsize=11, ssize=9)

# ── 连接边 ──────────────────────────────────────────
# 角色 → 中心
for i in range(5):
    x = 180 + i * 258
    c.arrow(x, 82 + 118, x, 232, color="#1d4ed8", sw=1.5, label="使用" if i < 3 else "触达 / 运维",
            lx=x, ly=214, marker="ahb")
# 外部依赖 → 中心
for i in range(6):
    y = 232 + 62 + i * 40 + 17
    c.arrow(328, y, 360, y, color="#c2410c", sw=1.4, marker="aho")
c.text(344, 232 + 300 - 6, "同步 / 异步", size=9, color="#9a3412", anchor="middle")
# 中心 → 下游
for i in range(4):
    y = 232 + 62 + i * 52 + 22
    c.arrow(1040, y, 1060, y, color="#6d28d9", sw=1.4, marker="ahb")

# ── 底部底座 ────────────────────────────────────────
c.band(560, 200, "复用底座（C-xx 云组件 / 基础设施，不新增中间件）",
       "证据 D18 §2 deploy/compose.yml：pgvector pg16 / Redis 7 / Keycloak 24 / RocketMQ 5.3.1 namesrv 与 broker / Jaeger",
       "#fffbeb", "#d97706", "compose 五服务")
comps = [
    ("C-01 PostgreSQL pgvector pg16", "状态 / checkpoint / outbox / 行锁"),
    ("C-02 Redis 7", "会话锁 / 事件流 / 限流"),
    ("C-03 RocketMQ 5.3.1", "namesrv + broker / 异步骨干"),
    ("C-04 Keycloak 24.0", "OIDC / SSO"),
    ("C-05 Jaeger all-in-one 1.57", "OTel trace 采集"),
]
for i, (t, s) in enumerate(comps):
    c.node(52 + i * 264, 560 + 66, 248, 62, t, s, "#fef3c7", "#f59e0b", tsize=10.5, ssize=9)
c.arrow(700, 532, 700, 560, color="#0f766e", sw=1.6, label="JDBC / RESP / MQ / OIDC / OTLP（同步持久化）",
        lx=700, ly=552, marker="ahg")

# ── 底部注释 ────────────────────────────────────────
c.rect(40, 788, W - 80, 122, "#ffffff", "#cbd5e1", rx=10, sw=1)
c.lines(58, 812, [
    "关系语义：使用（角色到系统）· 双向调用（E-01/E-04/E-05）· 数据推送（E-02 飞书 IM）· 消费（E-03 模型 Provider）· 订阅（E-06 设计基线读取）。",
    "边界继承：S0 §1.1~1.2 硬边界禁止云托管执行面与 biz 直连外部 SDK；B4 Amazon Bedrock Agents 已由 G2 否决（G3 §3.2 / D-02）。",
    "实证口径：本图节点全部来自 G4 代码检索（Grep / Glob / Read），15 域与 5 服务均为现存组件，未新增或裁剪任何对外能力；G6 前不动生产代码。",
], size=11, color="#475569", lh=25)

path = c.save(os.path.join(OUT, "system-context.svg"))
print("生成:", path)

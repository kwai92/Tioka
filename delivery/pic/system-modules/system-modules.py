#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 2 · 系统模块图（分层架构，G4 系统设计）

场景识别：分层架构图 / 云架构图。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案）。
自上而下四层：接入层 → 应用层（15 业务域）→ 框架与中间件层 → 集成对接层。
组件清单：接入层 5 类入口；应用层 15 域按四簇归组；framework 11 包 + infra/messaging；
         中间件 C-01~C-05；集成对接 E-01~E-06。
连接关系：接入层 HTTP / SSE 到应用层；应用层经 framework/messaging 契约到 infra-biz 实现；
         写 PostgreSQL（Outbox / checkpoint）与 RocketMQ（生产消费）；每线标注协议。
D-01 职责边界：framework/messaging 定义契约，infra-biz/messaging 实现 Outbox 与幂等投递，
         orchestration-biz 持有 SKIP LOCKED claim（orch_timer），三者分工在图中以橙色框标出。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1440, 1120
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 系统设计 · 图 2 系统模块图（分层架构）",
           "实证事实映射（G4）：四层自上而下；每线标注协议；C-xx / E-xx 编号对应 §3.1.3 清单；橙色为 D-01 职责边界")

# ── 接入层 ──────────────────────────────────────────
Y1, H1 = 82, 148
c.band(Y1, H1, "接入层", "管理端 / 工作台 / 开放 API / SSE 端点 / Webhook 接收端（证据 D16 §1 / D17 §3）",
       "#eff6ff", "#2563eb", "HTTP / SSE / Webhook")
access = [
    ("tioka-ui 前端", "Vue 3.5.12 / Chat / Approval / Observability"),
    ("tioka-server 启动面", "OpsFacade / AssetCatalog / MCP Outbound"),
    ("ThreadEventsController", "GET /api/runtime/threads/{id}/events · SSE"),
    ("开放 API 与 Webhook", "REST /api/v1 · 飞书入站"),
]
for i, (t, s) in enumerate(access):
    c.node(52 + i * 344, Y1 + 62, 328, 62, t, s, "#dbeafe", "#3b82f6", tsize=12, ssize=9.6)

# ── 应用层：15 域四簇 ───────────────────────────────
Y2, H2 = 250, 320
c.band(Y2, H2, "应用层（15 个业务域 · api 与 biz 双子模块）",
       "证据 D13 §2 pom.xml / D15 §1；设计基线 44-EA §1 仅列 12 域 + 可选 foundation，X8 判文档漂移（模块树未回写）",
       "#f0fdfa", "#0d9488", "15 域 / 32 api-biz")
clusters = [
    ("执行与对话簇（P0 主落点）", 52, ["runtime 173 · L1 / L4", "orchestration 87 · L3 claim", "chat 16", "infra 45 · L3 Outbox"]),
    ("资产与模型簇", 400, ["model 72", "agent 53", "mcp 22", "skill 12"]),
    ("知识与流程簇", 748, ["knowledge 44", "flow 55", "connector 68", "ontology 48"]),
    ("平台与治理簇", 1096, ["system 149", "governance 45", "eval 8"]),
]
for cname, cx, mods in clusters:
    c.rect(cx, Y2 + 62, 292, 240, "#ffffff", "#5eead4", rx=10, sw=1.3)
    c.text(cx + 146, Y2 + 86, cname, size=12, color="#0f766e", weight="bold", anchor="middle")
    for i, m in enumerate(mods):
        yy = Y2 + 100 + i * 48
        hot = ("L1" in m or "L3" in m or "L4" in m)
        c.node(cx + 12, yy, 268, 40, m, "api 契约 / biz 实现" if not hot else "P0 增量承载模块",
               "#fff7ed" if hot else "#ccfbf1", "#ea580c" if hot else "#14b8a6",
               tsize=11, ssize=9)

# 层间连线
for i in range(4):
    x = 216 + i * 344
    c.arrow(x, Y1 + H1, x, Y2, color="#1d4ed8", sw=1.5, label="HTTP / SSE", lx=x, ly=Y2 - 8, marker="ahb")

# ── 框架层（D-01 职责边界） ─────────────────────────
Y3, H3 = 590, 218
c.band(Y3, H3, "框架层（tioka-framework 11 包 + infra-biz/messaging 实现）",
       "契约与实现分离：framework 定义签名，infra-biz 落地 Outbox / 幂等投递 / RocketMQ 适配",
       "#fef2f2", "#dc2626", "D-01 职责边界")
# 左：framework 契约
c.rect(52, Y3 + 62, 420, 138, "#ffffff", "#f87171", rx=10, sw=1.4)
c.text(262, Y3 + 86, "framework/messaging（契约层 · 4 类）", size=12.5, color="#b91c1c", weight="bold", anchor="middle")
c.lines(70, Y3 + 110, [
    "EventPublisher（写 sys_outbox，Propagation.MANDATORY）",
    "DomainEvent / EventEnvelope（mq-envelope-v1）",
    "IdempotentConsumer（sys_consumed 去重抽象）",
    "error: ErrorCode / ApiResponse / BizException  ·  trace: TraceContext",
], size=10.5, color="#475569", lh=21)
# 中：infra-biz 实现
c.rect(492, Y3 + 62, 440, 138, "#fff7ed", "#ea580c", rx=10, sw=1.6)
c.text(712, Y3 + 86, "infra-biz/messaging（实现层 · 生产 + 幂等）", size=12.5, color="#9a3412", weight="bold", anchor="middle")
c.lines(510, Y3 + 110, [
    "OutboxEventPublisher → sys_outbox（事务内）",
    "OutboxRelay（Redis 领导锁 / markSent / 清理）",
    "IdempotentDeliveryService（sys_consumed + 死信）",
    "RocketMqMessageTransport（syncSend / Orderly）· ConsumerBootstrap",
], size=10.5, color="#475569", lh=21)
# 右：orchestration claim
c.rect(952, Y3 + 62, 436, 138, "#fff7ed", "#ea580c", rx=10, sw=1.6)
c.text(1170, Y3 + 86, "orchestration-biz（claim 层 · 行锁）", size=12.5, color="#9a3412", weight="bold", anchor="middle")
c.lines(970, Y3 + 110, [
    "OrchTimerMapper.claimDue：FOR UPDATE SKIP LOCKED",
    "OrchTimerPoller / OrchTimerService（CLAIM_LIMIT=100）",
    "6 个 IdempotentConsumer 子类（runtime / orch / chat / connector）",
    "注意：SKIP LOCKED 不用于 Outbox（Relay 走 Redis 领导锁）",
], size=10.5, color="#475569", lh=21)
# 应用层 → 框架层
c.arrow(700, Y2 + H2, 700, Y3, color="#0f766e", sw=1.8, label="api 契约调用（biz 只依赖 api + framework）",
        lx=700, ly=Y3 - 8, marker="ahg")
c.text(262, Y3 + 214, "① 定义契约", size=11, color="#b91c1c", weight="bold", anchor="middle")
c.text(712, Y3 + 214, "② 生产 / 幂等实现", size=11, color="#9a3412", weight="bold", anchor="middle")
c.text(1170, Y3 + 214, "③ 定时 claim", size=11, color="#9a3412", weight="bold", anchor="middle")

# ── 中间件层 ────────────────────────────────────────
Y4, H4 = 828, 132
c.band(Y4, H4, "中间件层（C-xx 云组件 · compose 五服务，复用不新增）", "", "#fffbeb", "#d97706", "C-01 ~ C-05")
mids = [
    ("C-01 PostgreSQL pgvector pg16", "sys_outbox / sys_consumed / runtime_turn / runtime_item"),
    ("C-02 Redis 7", "rt:lock · Redis Stream · RelayLock"),
    ("C-03 RocketMQ 5.3.1", "namesrv + broker · 顺序消费"),
    ("C-04 Keycloak 24.0", "OIDC / RBAC"),
    ("C-05 Jaeger 1.57", "OTel trace / traceparent"),
]
for i, (t, s) in enumerate(mids):
    c.node(52 + i * 268, Y4 + 62, 252, 56, t, s, "#fef3c7", "#f59e0b", tsize=10.5, ssize=8.8)
for x, lab in [(270, "JDBC / SQL"), (712, "RESP / MQ"), (1170, "OTLP")]:
    c.arrow(x, Y3 + H3, x, Y4, color="#0f766e", sw=1.4, label=lab, lx=x, ly=Y4 - 6, marker="ahg")

# ── 集成对接层 ──────────────────────────────────────
Y5, H5 = 980, 110
c.band(Y5, H5, "集成对接层（E-xx 外部依赖）", "", "#f5f3ff", "#7c3aed", "E-01 ~ E-06")
exts = [
    ("E-01 Keycloak / SSO", "OIDC"), ("E-02 飞书 IM 与开放平台", "HTTPS / Webhook"),
    ("E-03 OpenAI 兼容 Provider", "HTTPS / SSE · 默认 mock"),
    ("E-04 MCP over HTTP", "SDK 适配"), ("E-05 业务系统 / 知识源", "经 Connector"),
    ("E-06 docs/architecture", "唯一权威基线"),
]
for i, (t, s) in enumerate(exts):
    c.node(52 + i * 222, Y5 + 62, 210, 40, t, s, "#ede9fe", "#8b5cf6", tsize=10, ssize=8.5)
c.arrow(700, Y4 + H4, 700, Y5, color="#6d28d9", sw=1.4, label="SDK / API 适配（biz 禁直连外部 SDK · S0 硬边界）",
        lx=700, ly=Y5 - 6, marker="ahb")

path = c.save(os.path.join(OUT, "system-modules.svg"))
print("生成:", path)

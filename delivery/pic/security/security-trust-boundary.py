#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 1 · 信任边界图（G5 安全设计）

场景识别：网络拓扑 / 信任域划分。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案，复用 pic/_svgkit.py）。
本图为 Tioka 私有化 compose 的真实信任边界映射：无 VPC、无云子网、无云安全组、无堡垒机、无 WAF 硬件。
三条 Docker bridge network 为设计层逻辑分区，现状 compose 未声明 networks 段、G6 前不落地；现状 7 个中间件端口映射宿主机，compose 内无 8080 / 443 与应用容器，应用由本地进程启动，公网可达性待 U 系列取证。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 1010
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 安全设计 · 图 1 信任边界图",
           "实证映射（G5）：外部不可信域 / edge / app / data / devops 五域；虚线为信任边界；私有化 compose，无云资源")

# ── 外部不可信域 ──────────────────────────────────
c.band(80, 118, "① 外部不可信域（Internet）",
       "用户浏览器 tioka-ui（Vue 3）；外部依赖 E-01 至 E-05；HTTPS / TLS 1.2+ 入站",
       "#fef2f2", "#dc2626", "不可信 · 需鉴权")
ext = [
    ("用户浏览器 / tioka-ui", "Vue 3 · HTTP 与 SSE"),
    ("E-01 Keycloak 24 OIDC", "登录与 SSO"),
    ("E-02 飞书开放平台", "IM 与 Webhook"),
    ("E-03 Provider", "模型 HTTPS 与 SSE"),
    ("E-04 MCP over HTTP", "工具协议"),
]
for i, (t, s) in enumerate(ext):
    c.node(56 + i * 262, 80 + 62, 244, 50, t, s, "#fee2e2", "#ef4444", tsize=11.5, ssize=9.2)

# 信任边界线 ①
c.arrow(700, 198, 700, 232, color="#dc2626", sw=1.4, dash="6,4")
c.text(716, 220, "信任边界 ①：TLS 1.2+ 终结于反向代理；80 强制跳 443；无 DDoS 高防 / 无云 WAF（O7）",
       size=11, color="#b91c1c")

# ── DMZ / edge ────────────────────────────────────
c.band(238, 112, "② DMZ / tioka-edge（Docker bridge network）",
       "反向代理 / API 网关：TLS 终结、限流（替代 WAF 的入口管控）、X-Forwarded-For、EgressGuard 出网白名单",
       "#fff7ed", "#ea580c", "边缘信任域")
edge = [
    ("反向代理 / Nginx", "TLS 终结 · 443 · 限流"),
    ("EgressGuard", "allow-hosts · deny-cidrs"),
    ("入站校验", "Bearer 头 · 非法拒绝"),
]
for i, (t, s) in enumerate(edge):
    c.node(120 + i * 400, 238 + 60, 360, 46, t, s, "#ffedd5", "#f97316", tsize=12, ssize=9.5)

# 信任边界线 ②
c.arrow(700, 350, 700, 384, color="#ea580c", sw=1.4, dash="6,4")
c.text(716, 372, "信任边界 ②：BearerTokenFilter 业务细鉴权 + @RequirePermission 接口级拦截",
       size=11, color="#c2410c")

# ── 业务域 / app ──────────────────────────────────
c.band(390, 176, "③ 业务域 / tioka-app（Docker bridge network）",
       "tioka-server 启动面 + 15 业务域 api 与 biz 双子模块；全部无状态，权威状态外置",
       "#eff6ff", "#2563eb", "应用信任域")
app = [
    ("BearerTokenFilter", "tka_ / tk_live_ / JWT"),
    ("Principal", "不可变 · workspaceId"),
    ("ResourceGuard", "IDOR · AccessLevel"),
    ("RestExceptionAdvice", "统一错误码 · 无堆栈"),
    ("AuditApi / FileAuditSink", "DENIED · 独立卷"),
    ("@RequirePermission", "ArchUnit 门禁 ENG-10"),
]
for i, (t, s) in enumerate(app):
    col, row = i % 3, i // 3
    c.node(56 + col * 440, 390 + 66 + row * 54, 410, 46, t, s,
           "#dbeafe", "#3b82f6", tsize=12, ssize=9.5)

# 信任边界线 ③
c.arrow(700, 566, 700, 600, color="#2563eb", sw=1.4, dash="6,4")
c.text(716, 588, "信任边界 ③：compose network 隔离 + 宿主机 iptables；中间件不对公网暴露",
       size=11, color="#1d4ed8")

# ── 数据域 / data ─────────────────────────────────
c.band(606, 150, "④ 数据域 / tioka-data（Docker bridge network）",
       "compose 六服务（现状，含既有 jaeger）；凭证密文 AES-256-GCM（V145），无云 KMS / Vault / 对象存储",
       "#f0fdfa", "#0d9488", "数据信任域")
data = [
    ("PostgreSQL pgvector pg16", "secret_store · audit_event"),
    ("Redis 7", "rt:lock · token 哈希"),
    ("RocketMQ 5.3.1", "namesrv 加 broker"),
    ("Keycloak 24", "realm · 用户口令托管"),
    ("Jaeger 1.57", "OTel trace"),
]
for i, (t, s) in enumerate(data):
    c.node(56 + i * 262, 606 + 62, 244, 48, t, s, "#ccfbf1", "#14b8a6", tsize=11.2, ssize=9)

# 信任边界线 ④（运维）
c.arrow(700, 756, 700, 790, color="#0d9488", sw=1.4, dash="6,4")
c.text(716, 778, "信任边界 ④：运维通道与数据面分离；无堡垒机，由客户侧运维通道统一保障",
       size=11, color="#0f766e")

# ── 运维域 / devops ───────────────────────────────
c.band(796, 130, "⑤ 运维域 / devops（宿主机）",
       "本地运维账号与资源访问（模板 §2.3 云账号的本地等价）；数据不出境（S0 §1.1 至 1.2 / O6）",
       "#faf5ff", "#7c3aed", "运维信任域")
ops = [
    ("宿主机账号 + sudo 白名单", "禁 root 直登 · SSH 密钥"),
    ("环境变量注入", "TIOKA_*_MASTER_KEY"),
    ("配置文件 0600", "禁明文密钥入库"),
    ("本地备份卷", "全量 + WAL · 无跨地域"),
]
for i, (t, s) in enumerate(ops):
    c.node(56 + i * 330, 796 + 62, 306, 48, t, s, "#ede9fe", "#8b5cf6", tsize=11.2, ssize=9)

# 图例
c.lines(56, 962, [
    "图例：红色=不可信域；橙色=边缘；蓝色=应用；青色=数据；紫色=运维；虚线箭头=信任边界；实线=受控数据流。",
    "证据：deploy/compose.yml；系统设计 §3.1.3 / §6 / §7；UserStory §6.4；主理人 G5 重叠区权威方分配表（tioka-edge / tioka-app / tioka-data）。",
    "图注：443 / 80 跳 443 为设计层目标态，现状 compose 无 443 映射、应用由本地进程启动；三条 bridge 为设计层逻辑分区，现状 compose 未声明 networks 段、G6 前不落地；数据域 compose 六服务（含既有 jaeger）为现状事实。",
], size=10.5, color="#64748b", lh=16)

c.save(os.path.join(OUT, "security-trust-boundary.svg"))
print("saved security-trust-boundary.svg")

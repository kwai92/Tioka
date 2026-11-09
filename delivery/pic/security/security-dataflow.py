#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 2 · 敏感数据流图（G5 安全设计）

场景识别：数据流 / 关系图。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案，复用 pic/_svgkit.py）。
按系统设计 §3.5 接口契约 / §4 五张表 / §7.2 数据安全，把 L1 至 L4 敏感数据的产生、传输、加密、存储、审计、销毁串成可核查链路。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 900
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 安全设计 · 图 2 敏感数据流图",
           "L1 至 L4 分级驱动：TLS 入站 → BearerTokenFilter → Principal(workspaceId) → AES-256-GCM → 审计 / 脱敏 / 备份")

# 分级图例条
grades = [
    ("L1 公开", "产品介绍 / 公开 API 文档", "#dcfce7", "#16a34a"),
    ("L2 内部", "业务配置 / RoutingPlan / Agent 元数据", "#fef9c3", "#ca8a04"),
    ("L3 敏感", "消息正文 / Turn 状态 / 审批单 / trace 日志", "#ffedd5", "#ea580c"),
    ("L4 高敏", "口令 / API Key / client secret / OAuth token", "#fee2e2", "#dc2626"),
]
for i, (t, s, fill, stroke) in enumerate(grades):
    c.node(56 + i * 330, 80 + 8, 306, 52, t, s, fill, stroke, tsize=12, ssize=9.2)

# 数据流主干（横向五段）
c.band(168, 118, "① 数据产生与入站", "tioka-ui 的 content / attachments / Last-Event-ID；Bearer 头携带 token，禁 URL 携带",
       "#eff6ff", "#2563eb", "入口")
sources = [
    ("TurnCreateRequest", "content ≤ 20000 · clientMsgId"),
    ("Authorization: Bearer", "tka_ / tk_live_ / JWT"),
    ("X-Workspace-Id", "逻辑隔离边界"),
]
for i, (t, s) in enumerate(sources):
    c.node(56 + i * 440, 168 + 62, 410, 46, t, s, "#dbeafe", "#3b82f6", tsize=12, ssize=9.5)

c.band(310, 118, "② 传输与鉴权", "公网 TLS 1.2+；内部首批不启用 mTLS（系统设计 §6.3）；数据库 SSL 未启用列漂移",
       "#eff6ff", "#2563eb", "传输")
trans = [
    ("TLS 1.2+ 终结", "禁用 SSLv3 / TLS 1.0 / 1.1"),
    ("BearerTokenFilter", "验签 · audience · nonce"),
    ("Principal", "不可变 · workspaceId 注入"),
]
for i, (t, s) in enumerate(trans):
    c.node(56 + i * 440, 310 + 62, 410, 46, t, s, "#dbeafe", "#3b82f6", tsize=12, ssize=9.5)

c.band(452, 130, "③ 加密与存储", "CredentialCipher AES-256-GCM（v{ver}:base64(iv|ct|tag)）+ AAD 绑定；V145 ECB→GCM 重加密",
       "#f0fdfa", "#0d9488", "落盘")
stores = [
    ("secret_store / sys_user_credential", "L4 凭证密文 · 无 KMS"),
    ("runtime_turn / runtime_item", "L3 消息与状态 · workspace_id"),
    ("sys_outbox / sys_consumed", "L2 事件载荷 payload_json"),
    ("audit_event / audit.jsonl", "DENIED / SUCCESS 留痕"),
]
for i, (t, s) in enumerate(stores):
    col, row = i % 2, i // 2
    c.node(56 + col * 660, 452 + 62 + row * 40, 630, 34, t, s,
           "#ccfbf1", "#14b8a6", tsize=11.2, ssize=8.8)

c.band(606, 118, "④ 使用与外发", "展示脱敏（手机号 / 凭证中间位屏蔽）；导出审批 + 水印；EgressGuard 出网白名单拦截 SSRF",
       "#fff7ed", "#ea580c", "使用")
uses = [
    ("展示脱敏 + 导出审批", "操作人 + 时间水印"),
    ("EgressGuard", "allow-hosts · deny-cidrs"),
    ("日志脱敏", "禁打印凭证 / 正文 / 手机号"),
]
for i, (t, s) in enumerate(uses):
    c.node(56 + i * 440, 606 + 62, 410, 46, t, s, "#ffedd5", "#f97316", tsize=12, ssize=9.5)

c.band(748, 118, "⑤ 销毁与容灾", "逻辑删除 deleted_at + 定期物理清理；本地备份卷全量 + WAL；RPO ≤ 5 分钟 / RTO ≤ 30 分钟（引用系统设计 §4.3.2）",
       "#faf5ff", "#7c3aed", "收口")
ends = [
    ("逻辑删除 + 物理清理", "终态 180 天归档"),
    ("本地备份卷", "无跨地域对象存储"),
    ("数据不出境", "S0 §1.1 至 1.2 / O6"),
]
for i, (t, s) in enumerate(ends):
    c.node(56 + i * 440, 748 + 62, 410, 46, t, s, "#ede9fe", "#8b5cf6", tsize=12, ssize=9.5)

# 主干箭头（连接五个 band 的中缝）
for y in (286, 428, 582, 724):
    c.arrow(700, y, 700, y + 22, color="#475569", sw=2.0, marker="ah")

# 图例 / 证据
c.lines(56, 884, [
    "证据：BearerTokenFilter.java / CredentialCipher.java / V145__CredentialReencrypt.java / FileAuditSink.java / application.yml；系统设计 §3.5 / §4 / §7.2 / §8.2；UserStory §6.4.6。",
], size=10.5, color="#64748b")

c.save(os.path.join(OUT, "security-dataflow.svg"))
print("saved security-dataflow.svg")

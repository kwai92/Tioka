#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tioka · 业务闭环图生成器（主链路 + 待补链路 + 反馈回路）
由 business-architect（许边界）在 G3 阶段直接调用 diagrams-generator 产出，零依赖 SVG。
"""

import os

W, H = 1360, 760
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
SVG_PATH = os.path.join(OUT_DIR, "business-closure.svg")
FONT = "PingFang SC, Hiragino Sans GB, Microsoft YaHei, Noto Sans CJK SC, sans-serif"
parts = []


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def rect(x, y, w, h, fill, stroke, rx=8, sw=1.2, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')


def text(x, y, s, size=13, color="#1f2937", weight="normal", anchor="start"):
    parts.append(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                 f'fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{esc(s)}</text>')


def step(x, y, w, h, idx, title, sub, fill, stroke):
    rect(x, y, w, h, fill, stroke, rx=9, sw=1.5)
    rect(x + 12, y + 12, 26, 22, stroke, stroke, rx=11, sw=0)
    text(x + 25, y + 28, idx, size=12, color="#ffffff", weight="bold", anchor="middle")
    text(x + w / 2, y + 58, title, size=13.5, color="#111827", weight="bold", anchor="middle")
    text(x + w / 2, y + 78, sub, size=10.5, color="#4b5563", anchor="middle")


def arrow(x1, y1, x2, y2, color, sw=2.2, dash=None, marker="ah"):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
                 f'stroke-width="{sw}"{d} marker-end="url(#{marker})"/>')


parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">')
parts.append(
    '<defs>'
    '<marker id="ah" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#1d4ed8"/></marker>'
    '<marker id="ahp" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#7c3aed"/></marker>'
    '<marker id="ahg" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#0f766e"/></marker>'
    '</defs>'
)
parts.append(f'<rect width="{W}" height="{H}" fill="#f8fafc"/>')
text(40, 38, "Tioka 业务闭环 · 现有主链路 / 待补链路 / 反馈回路", size=21, color="#0f172a", weight="bold")
text(40, 60, "主链路依据 40-BA §5.1 PoC 黄金路径与 S0 §5 生命周期走 MQ；蓝色为已可描述主链，橙色为首批待补，紫色为反馈回路",
     size=11.5, color="#64748b")

# ── 主链路（两行折返）───────────────────────────────────
text(40, 100, "业务主链路（端到端，现有设计已描述、代码为 slice 级）", size=14, color="#1d4ed8", weight="bold")
row1 = [
    ("1", "登录与身份", "Keycloak OIDC / Workspace", "#dbeafe", "#3b82f6"),
    ("2", "Provider 与 RoutingPlan", "模型接入与路由绑定", "#dbeafe", "#3b82f6"),
    ("3", "Agent 发布与 MCP Grant", "资产控制面与授权", "#dbeafe", "#3b82f6"),
    ("4", "会话发消息 SSE delta", "Runtime thread / turn / item", "#dbeafe", "#3b82f6"),
    ("5", "工具结果回流", "ToolGateway / 工作盘", "#dbeafe", "#3b82f6"),
    ("6", "轨迹与审计归档", "Trajectory / AuditEvent", "#dbeafe", "#3b82f6"),
]
x0, y0, bw, bh, gap = 40, 116, 202, 96, 22
for i, (idx, t, s, f, st) in enumerate(row1):
    x = x0 + i * (bw + gap)
    step(x, y0, bw, bh, idx, t, s, f, st)
    if i < len(row1) - 1:
        arrow(x + bw, y0 + bh / 2, x + bw + gap, y0 + bh / 2, "#1d4ed8", sw=2.6)

# ── 待补链路（首批 P0）──────────────────────────────────
text(40, 268, "待补链路（首批 P0 / MVP，本轮链路规划冻结的实现对象）", size=14, color="#b45309", weight="bold")
gaps = [
    ("P0-1", "Runtime 状态落库", "单机内存状态改为 PostgreSQL checkpoint 并按 thread_id 加锁"),
    ("P0-2", "假实现下线", "六个假实现 API 按 ENG-03 下线或替换，先核实 V6"),
    ("P0-3", "MQ 生命周期闭环", "事务 Outbox 加 IdempotentConsumer 加行锁 claim，核实 X13"),
    ("P0-4", "SSE 续传与审批恢复", "事件 id 单调递增加状态快照重放，审批不新开 Turn"),
]
x0, y1, bw2, bh2, gap2 = 40, 284, 316, 92, 20
for i, (idx, t, s) in enumerate(gaps):
    x = x0 + i * (bw2 + gap2)
    rect(x, y1, bw2, bh2, "#fef3c7", "#d97706", rx=9, sw=1.5, dash="6,4")
    rect(x + 12, y1 + 12, 46, 22, "#d97706", "#d97706", rx=11, sw=0)
    text(x + 35, y1 + 28, idx, size=11, color="#ffffff", weight="bold", anchor="middle")
    text(x + bw2 / 2, y1 + 56, t, size=13.5, color="#111827", weight="bold", anchor="middle")
    text(x + bw2 / 2, y1 + 76, s[:34], size=10, color="#78350f", anchor="middle")
    if s[34:]:
        text(x + bw2 / 2, y1 + 88, s[34:], size=10, color="#78350f", anchor="middle")
# 主链路 → 待补链路的挂接
arrow(250, y0 + bh, 250, y1 - 6, "#b45309", sw=1.6, dash="5,4")
text(262, 240, "P0 缺口回灌主链路（地基先于功能，34 号四原则）", size=10.5, color="#b45309")

# ── 反馈回路 ────────────────────────────────────────────
text(40, 428, "关键反馈回路（主链路完结后回流，构成闭环）", size=14, color="#6d28d9", weight="bold")
loops = [
    ("业务效果回路", "会话完结 / Turn 终态", "T+1 统计到策略中心", "优化路由与放权策略", "#ede9fe", "#7c3aed"),
    ("运营监控回路", "指标异常 / Gate 失败", "实时告警到运营与 SRE", "触发人工走查与续跑", "#ede9fe", "#7c3aed"),
    ("模型迭代回路", "人工标注 / 评测数据集", "周度评测到 Model Hub", "提升选型与输出质量", "#ede9fe", "#7c3aed"),
    ("一致性守护回路", "四态判定 / 冲突登记", "整改清单回写文档与模块树", "本轮新增，闭环漂移", "#fce7f3", "#be185d"),
]
x0, y2, bw3, bh3, gap3 = 40, 444, 316, 104, 20
for i, (t, trig, mech, val, f, st) in enumerate(loops):
    x = x0 + i * (bw3 + gap3)
    rect(x, y2, bw3, bh3, f, st, rx=9, sw=1.4)
    text(x + bw3 / 2, y2 + 26, t, size=13, color="#111827", weight="bold", anchor="middle")
    text(x + bw3 / 2, y2 + 48, "触发：" + trig, size=10.5, color="#4b5563", anchor="middle")
    text(x + bw3 / 2, y2 + 68, "机制：" + mech, size=10.5, color="#4b5563", anchor="middle")
    text(x + bw3 / 2, y2 + 88, "价值：" + val, size=10.5, color="#4b5563", anchor="middle")
# 主链路 → 反馈回路（右侧折返）
parts.append(
    f'<path d="M {40 + 5 * (bw + gap) + bw} {y0 + bh / 2} L 1330 {y0 + bh / 2} L 1330 {y2 + bh3 / 2} L 1300 {y2 + bh3 / 2}" '
    f'fill="none" stroke="#7c3aed" stroke-width="2" stroke-dasharray="7,4" marker-end="url(#ahp)"/>'
)
text(1330, 400, "业务闭环回流", size=11, color="#6d28d9", anchor="end")
# 反馈回路 → 待补链路（迭代）
parts.append(
    f'<path d="M 198 {y2} L 198 {y1 + bh2 + 6} " fill="none" stroke="#0f766e" '
    f'stroke-width="1.8" stroke-dasharray="5,4" marker-end="url(#ahg)"/>'
)

# ── 运营主线 ────────────────────────────────────────────
LY = 580
rect(40, LY, W - 80, 150, "#ffffff", "#e2e8f0", rx=12, sw=1.4)
text(62, LY + 26, "运营主线（与 §5.3 与 §6.5 呼应）", size=14, color="#0f172a", weight="bold")
cols = [
    ("每日", "平台与架构负责人 / 运维", "Surefire 330 用例、progress.md 自动完成度表、Gate 冒烟、MQ 积压与 claim in-flight", "红灯定位到域，登记四态判定"),
    ("每周", "业务方与产品负责人", "主链路 PoC 走查、切片 fidelity 与 exit 三级、轨迹与审计抽样", "确认 slice 到 fidelity 的推进，冻结漂移整改"),
    ("每月", "甲方决策者", "34 号 G0 至 G4 与 GF 里程碑、S0 §13 G1 至 G7 能力门禁（X9 两套标签不混用）", "排期与范围裁决，签署 Gate 判定"),
]
cw = (W - 120) / 3
for i, (freq, who, what, act) in enumerate(cols):
    x = 60 + i * cw
    rect(x, LY + 40, cw - 20, 94, "#f1f5f9", "#cbd5e1", rx=8, sw=1)
    text(x + 14, LY + 62, freq + " · " + who, size=12, color="#0f172a", weight="bold")
    text(x + 14, LY + 82, "看：" + what[:40], size=10, color="#475569")
    if what[40:]:
        text(x + 14, LY + 96, what[40:80], size=10, color="#475569")
    if what[80:]:
        text(x + 14, LY + 110, what[80:], size=10, color="#475569")
    text(x + 14, LY + 126, "做：" + act, size=10, color="#475569")

parts.append("</svg>")
with open(SVG_PATH, "w", encoding="utf-8") as f:
    f.write("\n".join(parts))
print("generated:", SVG_PATH, os.path.getsize(SVG_PATH), "bytes")

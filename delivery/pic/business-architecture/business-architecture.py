#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tioka 代码与设计一致性校验 · 三层业务架构图生成器
由 business-architect（许边界）在 G3 阶段直接调用 diagrams-generator 产出。

场景识别：系统架构图 / 分层架构图。
工具选择：环境无 graphviz、diagrams、matplotlib、cairosvg、Pillow，
         故采用「方案 A：Mermaid 内嵌 + 方案 B：Python 标准库零依赖 SVG 矢量」组合，
         不引入任何第三方依赖，输出 SVG 矢量图 + 源码。
方向：TB（自上而下三层），接入层 → 业务能力层 → 基础能力层。
"""

import os

W, H = 1360, 1040
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
SVG_PATH = os.path.join(OUT_DIR, "business-architecture.svg")

FONT = "PingFang SC, Hiragino Sans GB, Microsoft YaHei, Noto Sans CJK SC, sans-serif"

parts = []


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def rect(x, y, w, h, fill, stroke, rx=8, sw=1.2, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    parts.append(
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
        f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>'
    )


def text(x, y, s, size=13, color="#1f2937", weight="normal", anchor="start", family=FONT):
    parts.append(
        f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" '
        f'fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{esc(s)}</text>'
    )


def node(x, y, w, h, title, sub, fill, stroke, tsize=13, ssize=10.5):
    rect(x, y, w, h, fill, stroke, rx=7, sw=1.1)
    text(x + w / 2, y + (h / 2 if not sub else h / 2 - 5), title,
         size=tsize, color="#111827", weight="bold", anchor="middle")
    if sub:
        text(x + w / 2, y + h / 2 + 13, sub, size=ssize, color="#4b5563", anchor="middle")


def arrow(x1, y1, x2, y2, color="#475569", sw=1.6, dash=None, label=None, lx=None, ly=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    parts.append(
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        f'stroke-width="{sw}"{d} marker-end="url(#ah)"/>'
    )
    if label:
        text(lx if lx is not None else (x1 + x2) / 2,
             ly if ly is not None else (y1 + y2) / 2 - 6,
             label, size=10.5, color="#475569", anchor="middle")


def layer_band(y, h, title, subtitle, fill, stroke, badge):
    rect(40, y, W - 80, h, fill, stroke, rx=14, sw=1.6)
    rect(40, y, 8, h, stroke, stroke, rx=3, sw=0)
    text(66, y + 30, title, size=17, color="#111827", weight="bold")
    text(66, y + 51, subtitle, size=11.5, color="#6b7280")
    rect(W - 232, y + 16, 176, 26, stroke, stroke, rx=13, sw=0)
    text(W - 144, y + 34, badge, size=11, color="#ffffff", weight="bold", anchor="middle")


# ── 画布 ────────────────────────────────────────────────
parts.append(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
    f'viewBox="0 0 {W} {H}" font-family="{FONT}">'
)
parts.append(
    '<defs>'
    '<marker id="ah" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
    'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#475569"/></marker>'
    '<marker id="ahb" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
    'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#1d4ed8"/></marker>'
    '<marker id="ahg" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
    'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#0f766e"/></marker>'
    '</defs>'
)
parts.append(f'<rect width="{W}" height="{H}" fill="#f8fafc"/>')
text(40, 38, "Tioka 代码与设计一致性校验 · 三层业务架构", size=22, color="#0f172a", weight="bold")
text(40, 60, "设计基线与代码现状的事实映射；粗线为主链路，实线为同步调用，虚线为异步事件；标注 X 编号处为本轮待裁决/待补链路",
     size=11.5, color="#64748b")

# ── 接入层 ──────────────────────────────────────────────
Y1, H1 = 82, 172
layer_band(Y1, H1, "接入层", "用户触点、启动入口与外部系统接入（代码证据 D16 / D17 / D18）",
           "#eff6ff", "#2563eb", "触点与网关")
node(70, Y1 + 66, 300, 88, "tioka-ui 前端", "Vue 3 / Chat / FlowStudio / Approval / Observability", "#dbeafe", "#3b82f6", 14)
node(390, Y1 + 66, 300, 88, "tioka-server 启动面", "TiokaServerApplication / OpsFacade / AssetCatalog / MCP Outbound", "#dbeafe", "#3b82f6", 14, 9.6)
node(710, Y1 + 66, 280, 88, "企业身份与 IM 接入", "Keycloak OIDC / 飞书 IM 与开放平台 / 业务系统", "#dbeafe", "#3b82f6", 14, 10)
node(1010, Y1 + 66, 280, 88, "协议与模型接入", "MCP over HTTP / OpenAI 兼容 Provider / SSE", "#dbeafe", "#3b82f6", 14, 10)

# ── 业务能力层 ──────────────────────────────────────────
Y2, H2 = 282, 372
layer_band(Y2, H2, "业务能力层", "15 个业务域，每域 api 与 biz 双子模块（代码证据 D13 §2 / D15 §1；设计基线 44-EA §1 仅列 13，见 X8）",
           "#f0fdfa", "#0d9488", "15 个业务域")

clusters = [
    ("执行与对话簇", 70, ["runtime 173", "orchestration 87", "chat 16"]),
    ("资产与模型簇", 390, ["model 72", "agent 53", "mcp 22", "skill 12"]),
    ("知识与流程簇", 710, ["knowledge 44", "flow 55", "connector 68", "ontology 48"]),
    ("平台与治理簇", 1010, ["system 149", "infra 45", "governance 45", "eval 8"]),
]
for cname, cx, mods in clusters:
    rect(cx, Y2 + 66, 280, 284, "#ffffff", "#5eead4", rx=10, sw=1.3)
    text(cx + 140, Y2 + 90, cname, size=13, color="#0f766e", weight="bold", anchor="middle")
    for i, m in enumerate(mods):
        yy = Y2 + 104 + i * 59
        is_new = m.split()[0] in ("ontology", "governance", "eval")
        fill = "#fef3c7" if is_new else "#ccfbf1"
        stroke = "#d97706" if is_new else "#14b8a6"
        node(cx + 14, yy, 252, 48, m, "api 与 biz 双子模块" if not is_new else "X8：ADR 补充、模块树未回写",
             fill, stroke, tsize=12.5, ssize=9.6)

# 业务能力层内部 MQ 异步连线（X13 待实测）
parts.append(
    f'<line x1="350" y1="{Y2 + 208}" x2="390" y2="{Y2 + 208}" stroke="#0f766e" '
    f'stroke-width="1.5" stroke-dasharray="5,4" marker-end="url(#ahg)"/>'
)
text(370, Y2 + 198, "MQ", size=10, color="#0f766e", anchor="middle")
parts.append(
    f'<line x1="670" y1="{Y2 + 208}" x2="710" y2="{Y2 + 208}" stroke="#0f766e" '
    f'stroke-width="1.5" stroke-dasharray="5,4" marker-end="url(#ahg)"/>'
)
text(690, Y2 + 198, "MQ", size=10, color="#0f766e", anchor="middle")
parts.append(
    f'<line x1="990" y1="{Y2 + 208}" x2="1010" y2="{Y2 + 208}" stroke="#0f766e" '
    f'stroke-width="1.5" stroke-dasharray="5,4" marker-end="url(#ahg)"/>'
)

# ── 基础能力层 ──────────────────────────────────────────
Y3, H3 = 682, 236
layer_band(Y3, H3, "基础能力层", "框架、契约、迁移、中间件与工程可观测（代码证据 D11 / D14 / D16 / D18 / D19）",
           "#fefce8", "#ca8a04", "底座与数据")
node(70, Y3 + 66, 300, 90, "tioka-framework", "11 包 / 53 main：security / error / messaging / persistence / trace", "#fef9c3", "#eab308", 14, 9.6)
node(390, Y3 + 66, 300, 90, "契约与迁移", "W0-17 冻结契约 / 45 SQL / 39 events JSON / 41 Flyway 迁移", "#fef9c3", "#eab308", 14, 9.6)
node(710, Y3 + 66, 280, 90, "compose 五服务", "PostgreSQL pgvector / Redis 7 / Keycloak 24 / RocketMQ namesrv 与 broker", "#fef9c3", "#eab308", 14, 9.4)
node(1010, Y3 + 66, 280, 90, "工程与可观测", "Surefire 330 用例 / progress.md 自动生成 / OTel trace / 单源 publish 脚本", "#fef9c3", "#eab308", 14, 9.4)
text(70, Y3 + 190, "X13：S0 §5 规定生命周期走 MQ；现有 framework/messaging 三类 + compose broker 仅证明底座就绪，生产/消费闭环待 G4 按域实测",
     size=11, color="#92400e")
text(70, Y3 + 210, "X7：contracts/README 自述「尚未引入 Flyway」与 41 份迁移、application.yml flyway 配置段并存，判为文档漂移",
     size=11, color="#92400e")

# ── 层间主链路（粗线）────────────────────────────────────
for x in (220, 540, 850, 1150):
    parts.append(
        f'<line x1="{x}" y1="{Y1 + H1}" x2="{x}" y2="{Y2}" stroke="#1d4ed8" '
        f'stroke-width="3" marker-end="url(#ahb)"/>'
    )
for x in (220, 540, 850, 1150):
    parts.append(
        f'<line x1="{x}" y1="{Y2 + H2}" x2="{x}" y2="{Y3}" stroke="#475569" '
        f'stroke-width="1.6" marker-end="url(#ah)"/>'
    )
text(680, Y1 + H1 + 18, "主链路：登录 → Provider/RoutingPlan → Agent 发布 → MCP Grant → 会话 SSE delta → 工具结果回流（40-BA §5.1）",
     size=11.5, color="#1d4ed8", anchor="middle", weight="bold")
text(680, Y2 + H2 + 18, "同步持久化与契约依赖（api 只依赖 api 加 framework；biz 禁互依，44-EA §2 R1 至 R6）",
     size=11, color="#475569", anchor="middle")

# ── 反馈回路（一致性守护）────────────────────────────────
parts.append(
    f'<path d="M 1320 {Y3 + 120} L 1332 {Y3 + 120} L 1332 {Y2 + 120} L 1300 {Y2 + 120}" '
    f'fill="none" stroke="#7c3aed" stroke-width="1.8" stroke-dasharray="6,4" marker-end="url(#ah)"/>'
)
text(1332, Y2 + 110, "一致性守护反馈回路：四态判定 → 整改清单 → 回写模块树与文档单源",
     size=10.5, color="#7c3aed", anchor="end")

# ── 图例 ────────────────────────────────────────────────
LY = 946
rect(40, LY, W - 80, 74, "#ffffff", "#e2e8f0", rx=10, sw=1.2)
text(62, LY + 24, "图例", size=13, color="#0f172a", weight="bold")
parts.append(f'<line x1="120" y1="{LY + 20}" x2="164" y2="{LY + 20}" stroke="#1d4ed8" stroke-width="3" marker-end="url(#ahb)"/>')
text(174, LY + 24, "粗线 = 主链路（PoC 黄金路径）", size=11, color="#334155")
parts.append(f'<line x1="430" y1="{LY + 20}" x2="474" y2="{LY + 20}" stroke="#475569" stroke-width="1.6" marker-end="url(#ah)"/>')
text(484, LY + 24, "实线 = 同步调用", size=11, color="#334155")
parts.append(f'<line x1="640" y1="{LY + 20}" x2="684" y2="{LY + 20}" stroke="#0f766e" stroke-width="1.5" stroke-dasharray="5,4" marker-end="url(#ahg)"/>')
text(694, LY + 24, "虚线 = 异步事件（MQ，X13 闭环待实测）", size=11, color="#334155")
rect(1030, LY + 12, 16, 16, "#ccfbf1", "#14b8a6", rx=3, sw=1)
text(1052, LY + 24, "44-EA 已列 13 域", size=11, color="#334155")
rect(1030, LY + 40, 16, 16, "#fef3c7", "#d97706", rx=3, sw=1)
text(1052, LY + 52, "X8：三域代码已落地、模块树未回写", size=11, color="#334155")
text(62, LY + 58, "证据口径：事实=代码与文档实测（D 编号）；推断=按类名与配置判断、未做运行时验证；建议=本阶段裁决与下游转交",
     size=11, color="#64748b")

parts.append("</svg>")

with open(SVG_PATH, "w", encoding="utf-8") as f:
    f.write("\n".join(parts))

print("generated:", SVG_PATH)
print("size:", os.path.getsize(SVG_PATH), "bytes")

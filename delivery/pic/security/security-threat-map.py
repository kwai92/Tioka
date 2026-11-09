#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 3 · 威胁—缓解映射图（G5 安全设计）

场景识别：关系图 / 映射图。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案，复用 pic/_svgkit.py）。
左列 STRIDE 六类威胁 → 中列 Tioka 真实攻击面 → 右列可核查缓解控制点（带章节号与代码证据），形成威胁→缓解闭环。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 940
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 安全设计 · 图 3 威胁—缓解映射图",
           "STRIDE 六类全覆盖：每条威胁映射到 §2 至 §7 的具体机制 / 类 / 配置，不落到泛化结论")

# 表头
c.text(56, 108, "威胁类别（STRIDE）", size=13, color="#0f172a", weight="bold")
c.text(480, 108, "Tioka 真实攻击面", size=13, color="#0f172a", weight="bold")
c.text(930, 108, "缓解控制点（章节 → 机制 / 类 / 配置）", size=13, color="#0f172a", weight="bold")
c.p.append('<line x1="40" y1="118" x2="1360" y2="118" stroke="#cbd5e1" stroke-width="1.2"/>')

rows = [
    ("S · 仿冒 Spoofing", "OIDC 令牌伪造 / API Key 泄露 / token 重放",
     "§2.1 OidcIdPAdapter 验签 iss/aud/exp/nonce + PKCE；BearerTokenFilter 三类令牌分支；TokenService 哈希 + 重放撤销",
     "#fee2e2", "#dc2626"),
    ("T · 篡改 Tampering", "请求参数 / 审批版本 / MQ 载荷 / 状态落库",
     "§3.2 HMAC 与 nonce 校验；§4.3 乐观锁 version；sys_consumed 幂等；RuntimeTurnMapper FOR UPDATE 行锁；EnvelopeSchemaValidator",
     "#ffedd5", "#ea580c"),
    ("R · 否认 Repudiation", "关键操作无留痕 / 审批抵赖 / 出网抵赖",
     "§7.2 AuditApi.record / recordIndependent；AuditEvent 含 workspaceId+actorId+outcome+traceId+clientIp；FileAuditSink 独立卷；EgressGuard DENIED 审计",
     "#fef9c3", "#ca8a04"),
    ("I · 信息泄露 Disclosure", "凭证 / 消息正文 / 堆栈 / 日志 / 越权读取",
     "§3.1 数据分级 L1 至 L4；§3.3 AES-256-GCM（V145）；§4.2 RestExceptionAdvice 统一 6 位码无堆栈；§8.2 日志禁敏感字段；ResourceGuard 下推过滤",
     "#dbeafe", "#2563eb"),
    ("D · 拒绝服务 DoS", "SSE 连接洪泛 / 慢查询 / MQ 堆积 / 网关压测",
     "§4.3 / §5.2 反向代理限流 + 902004 全局/Workspace/IP/用户四级 QPS；pump.tryAcquireConnection 闸门 500 返 300001；Relay 领导锁 + 死信转人工",
     "#ede9fe", "#7c3aed"),
    ("E · 权限提升 Privilege", "水平越权 IDOR / 垂直越权 / 跨 Workspace",
     "§2.2 RBAC1 + @RequirePermission 双层；ResourceGuard.check 跨空间抛 NOT_FOUND + DENIED 审计；PersistenceProperties ScopeLine deny-by-default；WORKSPACE_FORBIDDEN 40302",
     "#ccfbf1", "#0d9488"),
]

y = 132
rowh = 118
for i, (name, surface, ctrl, fill, stroke) in enumerate(rows):
    yy = y + i * rowh
    c.rect(48, yy, 360, rowh - 12, fill, stroke, rx=9, sw=1.3)
    c.text(64, yy + 30, name, size=13.5, color="#0f172a", weight="bold")
    c.lines(64, yy + 54, [surface], size=10.8, color="#475569", lh=15)
    # 中列攻击面
    c.rect(432, yy, 452, rowh - 12, "#f8fafc", "#cbd5e1", rx=9, sw=1.1)
    c.lines(448, yy + 32, [surface], size=11.3, color="#1f2937", lh=17)
    # 右列缓解
    c.rect(908, yy, 444, rowh - 12, "#f0fdfa", "#14b8a6", rx=9, sw=1.1)
    # 简单折行：按 26 字断行
    txt = ctrl
    lines = []
    while len(txt) > 26:
        cut = txt.rfind("；", 0, 27)
        if cut < 10:
            cut = 26
        lines.append(txt[:cut + 1])
        txt = txt[cut + 1:]
    lines.append(txt)
    c.lines(922, yy + 28, lines[:4], size=10.6, color="#134e4a", lh=17)
    # 箭头
    c.arrow(410, yy + (rowh - 12) / 2, 430, yy + (rowh - 12) / 2, color="#64748b", sw=1.5, marker="ah")
    c.arrow(886, yy + (rowh - 12) / 2, 906, yy + (rowh - 12) / 2, color="#0d9488", sw=1.5, marker="ahg")

c.lines(56, 878, [
    "说明：六类威胁全部命中系统设计 §3.5 接口契约 / §4 五张表 / §6 网络 / §7 安全 / §8 可观测的真实对象；每条缓解措施均可回溯到代码类或配置键。",
    "证据：附录 A 四态判定表；系统设计 §7.2；OWASP 防护见 §4.1；MFA 与 Keycloak 非 mock 验收属 O4 Out-of-Scope，登记待确认而非本期承诺。",
], size=10.5, color="#64748b", lh=16)

c.save(os.path.join(OUT, "security-threat-map.svg"))
print("saved security-threat-map.svg")

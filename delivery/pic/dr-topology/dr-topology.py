#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 3 · 故障域与高可用演进拓扑（G5 部署设计 §3.3）

场景识别：系统架构图 / 容灾拓扑。工具选择：Python 标准库零依赖 SVG（与 G3 / G4 一致）。
组件清单：本期现状单实例 compose / 四层故障域 / 完整版演进路径三段。
连接关系：本期容错靠进程守护与降级，演进段依次为双实例 HA、外部中间件集群、
跨 AZ 容灾；演进段整体属 P2 / L10 与 O4 Out-of-Scope，本期不实现、不承诺。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 900
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 部署设计 · 图 3 故障域与高可用演进拓扑",
           "本期为单 AZ 单实例 compose（6 个 service 定义 = 5 冻结中间件 + jaeger）；四层故障域逐层给出容错机制与 RTO；演进路径三段均为规划，不写成本期已实现或已承诺")

# ── 本期现状 ────────────────────────────────────────
c.band(82, 150, "本期现状（G4 §5.2 与 §5.4 冻结基线）",
       "单 Region 数量 1、单 AZ 数量 1、单实例；RPO ≤ 5 分钟、RTO ≤ 30 分钟；可用性由私有化宿主机与运维方兜底",
       "#f0fdfa", "#0d9488", "现状")
cur = [
    ("单进程 tioka-server", "进程守护重启，状态从 PG 恢复", "预期 RTO ≤ 30 秒"),
    ("Redis 不可用", "从 runtime_item 补齐，BD-02 降级", "预期 RTO ≤ 1 分钟"),
    ("Broker 不可用", "Outbox 堆积，Relay 下轮重投，BD-03 降级", "预期 RTO ≤ 5 分钟"),
    ("PostgreSQL 不可用", "停止接入，从备份恢复", "预期 RTO ≤ 30 分钟"),
]
for i, (t, m, r) in enumerate(cur):
    c.node(52 + i * 330, 144, 314, 72, t, m, "#ccfbf1", "#0d9488", tsize=11, ssize=8.8)
    c.lines(66 + i * 330, 226, [r], size=10, color="#0f766e", weight="bold")

# ── 四层故障域 ──────────────────────────────────────
c.band(262, 214, "四层故障域划分（§3.3.1）",
       "对齐模板四层故障域要求；私有化单机下「单 AZ」与「单 Region」两层合并由冷备恢复承接，不虚构跨 AZ 自动切换能力",
       "#eff6ff", "#2563eb", "4 层")
domains = [
    ("L1 单容器 / 单进程", "单实例", "compose healthcheck 加进程守护重启", "≤ 30 秒"),
    ("L2 单中间件服务", "会话锁 / 事件流 / 异步骨干", "BD-02 与 BD-03 降级加 Outbox 重投", "≤ 5 分钟"),
    ("L3 单宿主机（等价单 AZ）", "全部主资源", "私有化冷备恢复，双实例 HA 属 P2 / L10", "≤ 30 分钟"),
    ("L4 单站点（等价单 Region）", "全部主资源", "跨 Region 数据同步不适用，数据不出境（O6）", "≤ 30 分钟"),
]
for i, (lv, scope, mech, rto) in enumerate(domains):
    y = 324 + i * 38
    c.rect(52, y, 1296, 34, "#ffffff" if i % 2 == 0 else "#f8fafc", "#bfdbfe", rx=5, sw=0.8)
    c.text(66, y + 22, lv, size=11, color="#1e3a8a", weight="bold")
    c.text(330, y + 22, scope, size=10.6, color="#334155")
    c.text(600, y + 22, mech, size=10.6, color="#334155")
    c.text(1200, y + 22, rto, size=10.6, color="#1d4ed8", weight="bold", anchor="middle")

# ── 完整版演进路径 ──────────────────────────────────
c.band(496, 214, "完整版演进路径（规划 · P2 / L10 与 O4 Out-of-Scope，本期不实现）",
       "三段依次为应用多副本、外部中间件集群化、跨 AZ 容灾；每段均需先完成 U-02 仓库外取证与用户裁决，G6 前不动代码",
       "#fff7ed", "#ea580c", "演进")
stages = [
    ("阶段 1 · 应用多副本", "min 1 / max 3 副本", "粘性会话可选；取消与 Steer 按 sessionId 路由到租约节点", "OpsFacade 与 OutboxRelay 领导锁"),
    ("阶段 2 · 外部中间件", "外置 PG / Redis / RocketMQ", "PG 读写分离、Redis 升配、Broker 分区扩容", "需 U-02 非 mock 与双实例验收记录"),
    ("阶段 3 · 跨 AZ 容灾", "多 AZ 部署", "跨 AZ 自动切换、备份演练台账 RPO 与 RTO", "本期无跨 Region 同步，O6 数据不出境"),
]
for i, (t, s1, s2, s3) in enumerate(stages):
    x = 52 + i * 440
    c.node(x, 558, 420, 60, t, s1, "#ffedd5", "#ea580c", tsize=12, ssize=9.5)
    c.lines(x + 14, 636, [s2, s3], size=10.2, color="#9a3412", lh=20)
    if i < 2:
        c.arrow(x + 420, 588, x + 440, 588, color="#c2410c", sw=1.6, marker="aho")

# ── 可用性来源拆分 ──────────────────────────────────
c.band(730, 96, "可用性来源拆分（G4 §5.2.4 冻结）",
       "基础设施兜底归运维与交付方；本系统自身保障归研发团队；强依赖外部故障策略 E-01 至 E-05 归研发团队",
       "#f5f3ff", "#7c3aed", "责任方")
c.lines(60, 790, [
    "基础设施兜底：私有化宿主机与 compose 6 个 service 进程（运维与交付方）",
    "本系统自身保障：RedisThreadLock 会话锁、OutboxRelay 领导锁、SKIP LOCKED claim、SSE 四步重放、幂等消费（研发团队）",
], size=10.6, color="#5b21b6", lh=22)

# ── 底部注释 ────────────────────────────────────────
c.rect(40, 838, W - 80, 48, "#ffffff", "#cbd5e1", rx=8, sw=1)
c.lines(58, 862, [
    "本期不把双 AZ 双活写为已实现或本期承诺；双实例 HA 与 Keycloak 非 mock 验收属 O4，由 G5 判定但不并入自动化绿灯（R-04）。",
], size=10.6, color="#475569")

path = c.save(os.path.join(OUT, "dr-topology.svg"))
print("生成:", path)

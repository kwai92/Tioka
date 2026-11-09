#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 4 · Runtime Turn 状态机图（G4 系统设计）

场景识别：状态机 / 决策树。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案）。
状态与迁移全部来自代码实证：V101__m0_runtime_kernel.sql（runtime_turn / runtime_item / runtime_approval）、
V114__m1_runtime_recovery_approval.sql（recover_count / WAITING_APPROVAL 索引 / idx_turn_waiting）、
RuntimeTurnMapper.java RT-07（RUNNING→WAITING_APPROVAL→QUEUED / CANCELLED）、
ThreadEventsService.open（活动 Turn 状态补发）、LeaseReaper（in-flight 丢弃）、RedisThreadLock（rt:lock 会话锁）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1360, 900
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 系统设计 · 图 4 Runtime Turn 状态机（L1 会话锁与 L4 审批恢复）",
           "实证事实映射（G4）：状态枚举来自 V101 / V114 与 RuntimeTurnMapper RT-07；橙色为 P0 增量约束（会话锁 / 崩溃续跑）")


def state(x, y, w, h, title, sub, fill, stroke, tsize=13):
    c.node(x, y, w, h, title, sub, fill, stroke, tsize=tsize, ssize=9.6)


def trans(x1, y1, x2, y2, label, color="#475569", dash=None, lx=None, ly=None, marker="ah", lsize=10):
    c.arrow(x1, y1, x2, y2, color=color, sw=1.6, dash=dash, label=label,
            lx=lx, ly=ly, marker=marker, lsize=lsize)


# ── 状态节点 ────────────────────────────────────────
state(60, 120, 190, 62, "QUEUED", "已入队 / 待派发", "#fef3c7", "#d97706")
state(330, 120, 190, 62, "RUNNING", "持有 rt:lock 会话锁 + 租约", "#dcfce7", "#16a34a")
state(600, 120, 210, 62, "WAITING_APPROVAL", "审批挂起（paused run）", "#ffedd5", "#ea580c", tsize=12)
state(880, 120, 190, 62, "WAITING_CHILD", "子 Agent 等待（ADR-027）", "#ffedd5", "#ea580c")
state(600, 300, 210, 62, "COMPLETED", "Turn 终态 / 归档轨迹", "#dbeafe", "#2563eb")
state(880, 300, 190, 62, "CANCELLED", "interrupt / 用户取消", "#fee2e2", "#dc2626")
state(330, 300, 190, 62, "FAILED", "LEASE_LOST / 执行失败", "#fee2e2", "#dc2626")
state(60, 300, 190, 62, "新建 Turn", "client_msg_id 幂等", "#e0e7ff", "#4f46e5")

# ── 迁移边 ──────────────────────────────────────────
trans(250, 140, 330, 140, "TurnDispatcher 派发 + FOR UPDATE 行锁", color="#0f766e", marker="ahg")
trans(520, 140, 600, 140, "RT-07 需审批 / 释放租约", color="#c2410c", marker="aho")
trans(810, 140, 880, 140, "子 Agent 委派", color="#c2410c", marker="aho")
trans(420, 182, 420, 300, "正常完成 / 写 item.completed", color="#1d4ed8", lx=470, ly=250, marker="ahb")
trans(705, 182, 700, 300, "审批通过 → 同一 Turn 续跑完成", color="#1d4ed8", lx=705, ly=250, marker="ahb")
trans(975, 182, 975, 300, "子 Agent 回执完成", color="#1d4ed8", lx=1010, ly=250, marker="ahb")
trans(705, 120, 705, 96, "审批决定 → 回到 QUEUED（不新开 Turn）", color="#c2410c", lx=705, ly=88, marker="aho")
c.p.append(f'<path d="M705,96 L155,96 L155,120" fill="none" stroke="#c2410c" '
           f'stroke-width="1.6" stroke-dasharray="6,3" marker-end="url(#aho)"/>')
trans(600, 168, 880, 168, "", color="#64748b")
trans(600, 320, 880, 320, "审批 interrupt → CANCELLED", color="#b91c1c", lx=740, ly=312, lsize=9.5)
trans(330, 168, 250, 168, "", color="#64748b")
trans(330, 240, 330, 300, "崩溃 / 租约丢失", color="#b91c1c", lx=270, ly=270)
trans(150, 182, 150, 300, "新建（同一 thread 内 seq 递增）", color="#4f46e5", lx=150, ly=250, marker="ahb")

# ── P0 增量约束框 ───────────────────────────────────
c.band(410, 252, "P0 增量约束（L1 状态落库与会话加锁 / L4 审批挂起恢复）",
       "代码级已证实的既有机制（橙色）与本轮仍需收口的增量（下方清单），不新建表、不引入新中间件",
       "#fff7ed", "#ea580c", "L1 + L4")
c.lines(66, 476, [
    "L1 已证实：runtime_turn / runtime_item / runtime_approval 落 PostgreSQL（V101）；V105 删除 parts_json，V104 Java 回填为 item；V128 更名 thread。",
    "L1 已证实：RuntimeTurnMapper.xml 有 SELECT 1 FROM runtime_thread WHERE id = #{threadId} FOR UPDATE（按 thread_id 行锁）；",
    "          RedisThreadLock 用 rt:lock:{threadId} 的 setIfAbsent + TTL + Lua 续期 / 释放（双实例互斥）。",
    "L1 待收口：StreamPump.subs / readPositions、MemoryItemSink.streaming、SignalBus.local 仍为 JVM 内存 Map（进程内订阅 / 读位置），",
    "          进程重启后需由 Redis Stream 与 DB item 补齐；EventStream 有 TTL（running / afterTurn），长时断线存在丢事件窗口。",
    "L4 已证实：EventId（seq 或 seq.deltaIndex）单调可比；ThreadEventsController 支持 Last-Event-ID 头与 after 参数双通道；",
    "          ThreadEventsService.open 四步重放（活动 Turn 状态 → DB item.completed → Redis Stream delta → StreamPump 订阅）。",
    "L4 已证实：V114 加 recover_count 与 idx_turn_waiting（WAITING_APPROVAL / WAITING_CHILD），RT-07 定义续跑与 interrupt 迁移。",
    "L4 待收口：rt:cur 清除后晚到连接无法从 DB 补到 item.discarded（仅 Redis 流式事件）；审批跨多次人审的快照完整性需契约测试锁定。",
], size=11, color="#475569", lh=22)

# ── 状态机迁移表（图内简表） ────────────────────────
c.rect(40, 682, W - 80, 200, "#ffffff", "#cbd5e1", rx=10, sw=1)
c.text(58, 708, "关键迁移（与 §3.2.M1.5 / §3.2.M3.5 状态机迁移表一致）", size=13, color="#0f172a", weight="bold")
cols = [58, 240, 470, 760, 1080]
heads = ["起始状态", "触发事件", "终止状态", "触发条件 / 校验", "副作用"]
for x, h in zip(cols, heads):
    c.text(x, 734, h, size=11, color="#1e3a8a", weight="bold")
rows = [
    ("QUEUED", "TurnDispatcher 派发", "RUNNING", "rt:lock 抢锁成功 + FOR UPDATE", "写 runtime_turn.status / lease_until"),
    ("RUNNING", "需人工审批", "WAITING_APPROVAL", "工具风险 ≥ WRITE / 策略命中", "释放租约，写 runtime_approval(PENDING)"),
    ("WAITING_APPROVAL", "审批通过", "QUEUED", "无 PENDING 审批 + ResumeSweeper", "同一 Turn 续跑，不新开 Turn"),
    ("WAITING_APPROVAL", "interrupt", "CANCELLED", "用户取消 / 审批驳回", "发 turn.cancelled SSE 事件"),
    ("RUNNING", "崩溃 / 租约丢失", "FAILED", "recover_count ≥ maxRecover", "LEASE_LOST + LeaseReaper 丢弃 in-flight"),
    ("RUNNING", "工具与模型完成", "COMPLETED", "item.completed 全部落库", "写轨迹 / 发 turn.finished 事件"),
]
for i, row in enumerate(rows):
    yy = 758 + i * 20
    for x, cell in zip(cols, row):
        c.text(x, yy, cell, size=9.8, color="#334155")

path = c.save(os.path.join(OUT, "system-statemachine.svg"))
print("生成:", path)

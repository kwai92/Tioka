#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tioka G4 UserStory · P0 首批链路泳道图（零依赖 SVG，Python 标准库）。
场景识别：流程泳道图。四条泳道分别对应 L1–L4（G3 §4.5，经中间确认方案 A 冻结），
每泳道贯穿「角色 → 触发 → 系统处理 → 代码级验收证据 → 异常路径」。
工具方案由主理人编排层锁定，复用 pic/_svgkit.py 绘图原语。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "userstory-swimlane.svg")
c = Canvas(1320, 900, "Tioka G4 UserStory · P0 首批链路泳道图",
           "L1–L4 四条地基链路（G3 §4.5，经中间确认 · 用户裁决方案 A 冻结）；G6 通过后才进入代码实现，G6 前不动生产代码")

COLS = [("角色", 40, 150), ("触发 / 输入", 200, 210), ("系统处理（研发可拆任务）", 420, 350),
        ("代码级验收证据", 780, 300), ("异常路径", 1090, 190)]
for name, x, w in COLS:
    c.rect(x, 86, w, 34, "#1e293b", "#1e293b", rx=5, sw=0)
    c.text(x + w / 2, 108, name, size=12, color="#ffffff", weight="bold", anchor="middle")

LANES = [
    ("L1 · Runtime 状态落库\n与按 thread_id 会话加锁", "#eff6ff", "#1d4ed8",
     "开发与测试工程师\n（F6 / P0 / MVP）",
     ["会话发起 / 进程重启", "同一 thread_id 并发请求", "D7 §01 单机内存状态", "D16 §2 迁移基线"],
     ["状态持久化落现有 PostgreSQL", "checkpoint 表 + 按 thread_id 行锁", "禁止用内存态 / H2 临时代替", "不改 compose 五服务"],
     ["进程重启后状态可恢复", "同 thread 并发串行化", "TurnStatus 契约测试绿", "G3 §4.5 L1 退出标准"],
     ["状态丢失 → 从 checkpoint 恢复", "进程重启错写到内存态 → 拒绝", "重复加锁 → 无死锁 / 无覆盖"]),
    ("L2 · 六个假实现 API\n下线或替换", "#fef2f2", "#b91c1c",
     "开发与测试工程师\n（F7 / P0 / MVP）",
     ["34 号四原则「假实现先下线」", "V6 待按 D7 §01 表名检索", "六个假实现类名清单", "ENG-03 下线要求"],
     ["逐类检索六类假实现", "每类给「仍存在/已下线/已替换」", "下线或替换并登记台账", "本报告不假设其已下线"],
     ["六类结论均可检索", "误调用假实现显式报错", "六类清单与 D7 §01 对齐", "G3 §4.5 L2 退出标准"],
     ["假实现被误调用 → 显式异常", "六类之外新增假实现 → 台账补登", "下线后契约测试回归绿"]),
    ("L3 · MQ 生产 / 消费 /\n幂等三点核验与补链", "#f0fdfa", "#0f766e",
     "开发与测试工程师\n（F8 / P0 / MVP）",
     ["S0 §5 生命周期走 MQ", "X13 裁定闭环未证实", "framework/messaging 三类", "D14 §3 / D18 §2 broker"],
     ["按域实测生产 / 消费 / 幂等", "幂等键用事件 ID", "先按 V5 收敛双份 SQL 副本", "D-01 职责边界转交 system-architect"],
     ["三点各标注已证实/未证实/待补", "给出按域实测入口", "V4 三点结论 3 / 3", "BA-05 未实测前不改判「一致」"],
     ["重复消息 → 幂等消费去重", "消费崩溃 → in-flight 回收", "双份 SQL 版本不一致 → 先对账"]),
    ("L4 · SSE 事件续传与\n审批挂起恢复", "#fefce8", "#a16207",
     "业务方 / 开发与测试工程师\n（F9 / P0 / MVP）",
     ["PoC 黄金路径 SSE delta", "断线重连 / 审批介入", "Part Schema v0 / RuntimeEventType", "D5 §w0-contract-freeze"],
     ["事件 id 单调递增持久化", "状态快照重放 + Last-Event-ID", "审批作为 paused run 不新开 Turn", "同轮不换模 / 不重复工具调用"],
     ["断线按事件 id 续传不丢上下文", "审批跨多次不新开 Turn", "RuntimeEventSchema 契约测试绿", "G3 §4.5 L4 退出标准"],
     ["断线重连 → 事件 id 续传", "审批超时 → 挂起保留可重试", "审批重复提交 → 幂等不重开", "事件 id 回退 → 拒绝回放"]),
]

y0, lane_h = 126, 186
for i, (title, fill, stroke, role, trig, proc, ac, exc) in enumerate(LANES):
    y = y0 + i * lane_h
    c.rect(40, y, 1240, lane_h - 12, fill, stroke, rx=10, sw=1.4)
    c.rect(40, y, 8, lane_h - 12, stroke, stroke, rx=3, sw=0)
    # 泳道名
    for k, line in enumerate(title.split("\n")):
        c.text(58, y + 40 + k * 20, line, size=12, color="#0f172a", weight="bold")
    c.lines(58, y + 96, role.split("\n"), size=10.5, color=stroke, lh=16, weight="bold")
    # 四列内容
    for (name, x, w), arr in zip(COLS[1:], (trig, proc, ac, exc)):
        c.rect(x, y + 14, w - 12, lane_h - 40, "#ffffff", stroke, rx=7, sw=0.9)
        c.lines(x + 12, y + 36, arr, size=10.2, color="#334155", lh=22)
    # 列间箭头
    for bx in (196, 416, 776, 1086):
        c.arrow(bx - 6, y + (lane_h - 12) / 2, bx + 4, y + (lane_h - 12) / 2,
                color=stroke, sw=1.5, marker="ah")

c.rect(40, 876, 1240, 0.6, "#cbd5e1", "#cbd5e1", rx=0, sw=0)
c.save(OUT)
print("saved", OUT)

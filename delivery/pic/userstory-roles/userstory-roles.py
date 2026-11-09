#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tioka G4 UserStory · 五类角色交互图（零依赖 SVG，Python 标准库）。
场景识别：角色关系图 / 分层交互图。工具方案由主理人在编排层锁定为
「Mermaid 内嵌 + Python 标准库零依赖 SVG」，与 G3 §5.1 一致，不引入第三方依赖。
复用 pic/_svgkit.py 绘图原语。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "userstory-roles.svg")
c = Canvas(1240, 800, "Tioka G4 UserStory · 五类角色交互图",
           "G3 §2.1 冻结的五类角色 → 本文 §5 的 UserStory 产物（F1–F9）→ 复用底座；角色、模块、数值均来自 D 编号证据")


def card(x, y, w, h, title, arr, fill, stroke, tsize=12.5, lsize=10.5):
    c.rect(x, y, w, h, fill, stroke, rx=9, sw=1.2)
    c.text(x + 12, y + 21, title, size=tsize, color="#0f172a", weight="bold")
    c.lines(x + 12, y + 40, arr, size=lsize, color="#334155", lh=15)


# ① 角色层
c.band(88, 180, "① 角色层 · G3 §2.1 冻结的 5 类核心角色",
       "业务身份 / 主要操作 / 核心关注点逐行对齐 G3 §2.1；本文不新增第六类角色，不细分角色",
       "#eff6ff", "#1d4ed8", "5 类角色")
roles = [
    ("甲方决策者 · 平台与架构负责人", ["审阅四态判定、冲突裁决", "签署 Gate、冻结链路优先级", "关注：基线唯一、结论可追溯"]),
    ("最终用户 A · 业务方与产品负责人", ["发起会话、编排流程", "审批放权、查看轨迹", "关注：PoC 黄金路径可演示"]),
    ("最终用户 B · 开发与测试工程师", ["契约核对、切片交付", "跑 Gate 冒烟与集成测试", "关注：P0 清单可直接拆任务"]),
    ("受影响方 · 终端用户", ["发消息、观察 SSE delta", "查看工具结果回流", "关注：续传与审批恢复不丢上下文"]),
    ("受影响方 · 运维与交付方 SRE", ["部署、监控指标与告警", "Gate 走查、备份与 HA", "关注：绿灯不等于 Exit"]),
]
for i, (t, arr) in enumerate(roles):
    card(55 + i * 220, 150, 210, 100, t, arr, "#ffffff", "#93c5fd", tsize=11)

# ② UserStory 产物层
c.band(300, 180, "② UserStory 产物层 · 本文 §5 五条旅程",
       "F1–F9 均为 P0 且逐条标记 MVP；US-2 至 US-5 分别对应 L1 至 L4 四条地基链路",
       "#ecfdf5", "#0f766e", "5 条 US / 9 条 P0")
uss = [
    ("US-1 校验基线消费与冻结", ["F1–F5；16 条四态判定", "14 条冲突裁决台账", "34 号 6 项核对 + 10 条链路"]),
    ("US-2 L1 状态落库", ["F6；checkpoint 落 PostgreSQL", "按 thread_id 会话加锁", "进程重启可恢复 + 契约测试绿"]),
    ("US-3 L2 假实现下线", ["F7；按 D7 §01 六类名检索", "逐类给出仍存在/已下线/已替换", "结论可检索 + 误调用显式报错"]),
    ("US-4 L3 MQ 闭环核验", ["F8；framework/messaging 三类", "生产/消费/幂等三点按域实测", "各标注已证实/未证实/待补"]),
    ("US-5 L4 SSE 续传", ["F9；事件 id 单调递增", "状态快照重放 + Last-Event-ID", "审批跨多次不新开 Turn"]),
]
for i, (t, arr) in enumerate(uss):
    card(55 + i * 220, 362, 210, 100, t, arr, "#ffffff", "#5eead4", tsize=11)

# ③ 复用底座层
c.band(512, 180, "③ 复用底座层 · G3 §5.2 / §6.2（compose 五服务冻结，不新增中间件）",
       "15 业务域 + framework 11 包 + 41 份 Flyway + W0-17 冻结契约 + 工程信号；100% 复用",
       "#f8fafc", "#475569", "100% 复用")
base = [
    ("业务域与框架", ["15 个业务域（api + biz 双子模块）", "tioka-framework 11 包 / 53 main", "出处 D13 §2、D14 §2"]),
    ("契约与迁移", ["W0-17 冻结契约 / 39 份 events JSON", "41 份 Flyway + 45 份 contracts SQL", "双份副本按 V5 待对账（D16 §2）"]),
    ("compose 五服务", ["PostgreSQL pgvector、Redis 7", "Keycloak 24、RocketMQ namesrv", "RocketMQ broker（D18 §2）"]),
    ("工程与可观测", ["Surefire 330 用例 / progress.md", "Gate 冒烟、OTel trace", "绿灯不等于 Exit（D19 §2）"]),
]
for i, (t, arr) in enumerate(base):
    card(55 + i * 285, 574, 270, 100, t, arr, "#ffffff", "#cbd5e1", tsize=11.5)

# 层间消费箭头（层对层，不表示角色与 US 的逐一归属；逐一归属见本文 §4.2 场景清单）
for x in (160, 380, 600, 820, 1040):
    c.arrow(x, 250, x, 358, color="#1d4ed8", sw=1.5, marker="ahb")
    c.arrow(x, 462, x, 570, color="#0f766e", sw=1.5, marker="ahg")
c.text(620, 292, "消费 / 验收（层对层；逐一归属见 §4.2 关键场景清单）", size=10.5, color="#475569", anchor="middle")
c.text(620, 505, "复用既有底座，不新增运行时、不改 compose 五服务", size=10.5, color="#475569", anchor="middle")

# 边界注记
c.rect(40, 712, 1160, 62, "#fff7ed", "#c2410c", rx=10, sw=1.2)
c.lines(56, 734, [
    "边界纪律：Out-of-Scope O1–O8 逐条对齐 G3 §6.1（云托管执行面 O1、重做全量文档 O2、真实模型连跑 O3、",
    "双实例 HA / Keycloak 非 mock O4、G6 前改生产代码 O5、跨租户 SaaS O6、新增中间件 O7、越权方案 O8）。",
], size=10.5, color="#7c2d12", lh=17)

c.save(OUT)
print("saved", OUT)

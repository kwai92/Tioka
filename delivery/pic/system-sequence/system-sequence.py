#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 3 · 关键时序图（G4 系统设计，P0 的 L3 与 L4 链路，含异常分支）

场景识别：关键时序图 / 流程图。工具：Python 标准库零依赖 SVG（编排层 G3 已锁定方案）。
横向参与者分层：前端 / 后端 Controller / 应用服务 / 中间件（PG / Redis / RocketMQ）/ framework。
上半：L3 MQ 生产 → Outbox → Relay → Broker → 幂等消费（异常进死信）。
下半：L4 SSE 首连与 Last-Event-ID 断线续传 + 审批挂起恢复（不新开 Turn）。
异常分支：投递失败 markAttemptFailed / 消费重复 sys_consumed 命中跳过 / 末次失败写 sys_dead_letter /
         审批 interrupt 转 CANCELLED / SSE 连接数满返回 DEPENDENCY 错误码。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1480, 1440
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 系统设计 · 图 3 关键时序图（L3 MQ 闭环 + L4 SSE 续传）",
           "实证事实映射（G4）：同步调用实线、异步消息虚线；写操作标注事务边界 / 幂等键 / 行锁；含 5 条异常分支")


def lifelines(names, top, bottom):
    xs = [140 + i * 220 for i in range(len(names))]
    for x, n in zip(xs, names):
        c.rect(x - 92, top - 30, 184, 34, "#dbeafe", "#3b82f6", rx=6, sw=1)
        c.text(x, top - 9, n, size=10.2, color="#1e3a8a", weight="bold", anchor="middle")
        c.p.append(f'<line x1="{x}" y1="{top + 4}" x2="{x}" y2="{bottom}" stroke="#94a3b8" '
                   f'stroke-width="1" stroke-dasharray="5,4"/>')
    return xs


def msg(xs, i, j, y, label, color="#475569", dash=None, marker="ah", lsize=10):
    x1, x2 = xs[i], xs[j]
    c.arrow(x1, y, x2, y, color=color, sw=1.5, dash=dash, marker=marker)
    c.text((x1 + x2) / 2, y - 8, label, size=lsize, color=color, anchor="middle")


def selfcall(xs, i, y, label, color="#475569"):
    x = xs[i]
    c.arrow(x, y, x + 46, y, color=color, sw=1.3)
    c.p.append(f'<line x1="{x + 46}" y1="{y}" x2="{x + 46}" y2="{y + 22}" stroke="{color}" stroke-width="1.3"/>')
    c.arrow(x + 46, y + 22, x, y + 22, color=color, sw=1.3)
    c.text(x + 52, y + 17, label, size=9.3, color=color)


def note(y, label, x=52, w=None, color="#9a3412", fill="#fff7ed", stroke="#ea580c"):
    w = w or W - 104
    c.rect(x, y, w, 30, fill, stroke, rx=6, sw=1)
    c.text(x + 12, y + 20, label, size=10.4, color=color, weight="bold")


# ══ 上半：L3 MQ 生产-消费闭环 ═══════════════════════
c.text(52, 100, "链路 A · L3 MQ 生产 / 消费 / 幂等三点闭环（S0 §5 生命周期走 MQ；X13 判闭环未证实，本图为代码级实证）",
       size=13.5, color="#0f766e", weight="bold")
namesA = ["业务服务（biz）", "EventPublisher 契约", "OutboxEventPublisher", "sys_outbox（PG）", "OutboxRelay", "RocketMQ broker", "IdempotentDeliveryService"]
topA, botA = 150, 800
xsA = lifelines(namesA, topA, botA)

msg(xsA, 0, 1, 190, "① publish(DomainEvent) 业务事务内", color="#1d4ed8", marker="ahb")
msg(xsA, 1, 2, 222, "② Propagation.MANDATORY 强制已有事务", color="#1d4ed8", marker="ahb")
selfcall(xsA, 2, 252, "组装 EventEnvelope（eventId / traceId / traceparent / workspaceId / runAs）")
msg(xsA, 2, 3, 300, "③ INSERT sys_outbox（与业务实体同一事务）", color="#0f766e", marker="ahg")
msg(xsA, 2, 3, 328, "④ NOTIFY 通道唤醒中继", color="#0f766e", dash="6,3", marker="ahg")
note(364, "事务边界：业务实体写入与 sys_outbox 登记在同一个本地事务；投递在事务外，遵循 Transactional Outbox（SR-15）")
msg(xsA, 4, 3, 402, "⑤ findUnsent(batch) 按 id 顺序领取", color="#c2410c", marker="aho")
selfcall(xsA, 4, 430, "RedisRelayLock 领导锁单例 / 续期；同 msg_key 失败阻断本轮后续行")
msg(xsA, 4, 5, 492, "⑥ syncSend / syncSendOrderly（RocketMQTemplate）", color="#c2410c", dash="6,3", marker="aho")
msg(xsA, 4, 3, 520, "⑦ markSent(id, sentAt)；定期 deleteSentBefore 清理", color="#0f766e", marker="ahg")
note(556, "异常分支 1：投递失败 → markAttemptFailed(attempts+1, last_error)，下轮重投；relay 崩溃后重复发布由下游幂等吸收",
     color="#b91c1c", fill="#fef2f2", stroke="#f87171")
msg(xsA, 5, 6, 594, "⑧ DefaultMQPushConsumer / MessageListenerOrderly 顺序推送", color="#c2410c", dash="6,3", marker="aho")
msg(xsA, 6, 6, 624, "⑨ sys_consumed 主键 (consumer, eventId) 去重", color="#0f766e", marker="ahg")
note(660, "异常分支 2：INSERT ... ON CONFLICT DO NOTHING 影响 0 行 → 判定重复投递，直接 ACK 跳过，不重复执行业务",
     color="#b91c1c", fill="#fef2f2", stroke="#f87171")
selfcall(xsA, 6, 698, "W3C traceparent 提取 → runAs 身份 → requiredTx 内 dispatch 到 6 个 Consumer.handle")
note(760, "异常分支 3：末次 reconsumeTimes ≥ maxAttempts → requiresNewTx 写 sys_dead_letter（OPEN）并确认，不阻断整批",
     color="#b91c1c", fill="#fef2f2", stroke="#f87171")

# ══ 下半：L4 SSE 续传与审批恢复 ═════════════════════
c.text(52, 830, "链路 B · L4 SSE 事件续传与审批挂起恢复（B1 事件续传四件套 / B3 paused run 语义，仅借鉴契约不引入运行时）",
       size=13.5, color="#0f766e", weight="bold")
namesB = ["tioka-ui（Vue 3）", "ThreadEventsController", "ThreadEventsService", "runtime_turn / runtime_item（PG）", "EventStream（Redis Stream）", "StreamPump", "ApprovalDecideService"]
topB, botB = 870, 1400
xsB = lifelines(namesB, topB, botB)

msg(xsB, 0, 1, 910, "① GET /threads/{id}/events + Last-Event-ID 或 ?after", color="#1d4ed8", marker="ahb")
msg(xsB, 1, 2, 942, "② open(threadId, lastEventId) + requireVisible 鉴权", color="#1d4ed8", marker="ahb")
selfcall(xsB, 2, 972, "pump.tryAcquireConnection() 连接数闸门；超限抛 BizException(DEPENDENCY)")
msg(xsB, 2, 3, 1036, "③ 活动 Turn 状态补发（RUNNING / WAITING_APPROVAL / COMPLETED）", color="#0f766e", marker="ahg")
msg(xsB, 2, 3, 1064, "④ DB 补齐 item.completed（listAfterSeq 分页 500 直到追上）", color="#0f766e", marker="ahg")
msg(xsB, 2, 4, 1092, "⑤ Redis Stream range(rt:cur → L) 补 delta（XADD / XRANGE）", color="#c2410c", marker="aho")
msg(xsB, 2, 5, 1120, "⑥ pump.subscribe(threadId, L, sent, emitter) 后发 connected", color="#0f766e", marker="ahg")
note(1156, "单调事件 id：EventId 为 seq 或 seq.deltaIndex（Comparable），V101 注释「seq 即 SSE 事件 ID」；断线按游标去重不重复执行工具调用")
msg(xsB, 0, 1, 1194, "⑦ 断线重连：fetchEventSource 带 Last-Event-ID 头 + after 参数", color="#1d4ed8", marker="ahb")
msg(xsB, 1, 2, 1222, "⑧ EventId.parse(lastEventId) 定位续传起点，重放 ③~⑥", color="#1d4ed8", marker="ahb")
note(1258, "异常分支 4：rt:cur 已清除时，晚到连接无法从 DB 补到 item.discarded（仅 Redis 流式事件）→ 登记为 L4 待补点，见 §3.2.M3.5",
     color="#b91c1c", fill="#fef2f2", stroke="#f87171")
msg(xsB, 2, 6, 1296, "⑨ 审批挂起：RUNNING → WAITING_APPROVAL（释放租约）", color="#c2410c", marker="aho")
msg(xsB, 6, 3, 1324, "⑩ 审批通过 → WAITING_APPROVAL → QUEUED 同一 Turn 续跑", color="#0f766e", marker="ahg")
note(1360, "异常分支 5：审批 interrupt → CANCELLED；recover_count ≥ maxRecover → FAILED(LEASE_LOST)。审批是 paused run 非新 Turn（V114 / RT-07 / SR-09）",
     color="#b91c1c", fill="#fef2f2", stroke="#f87171")

path = c.save(os.path.join(OUT, "system-sequence.svg"))
print("生成:", path)

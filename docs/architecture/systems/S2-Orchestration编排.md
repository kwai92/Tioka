# S2 — Orchestration 编排（完整能力）

> 对标：agent-autoloop + dsh-superagent + 企业调度/审批  
> 范围：全量能力在表；波次只排序。

---

## 1. 定位与边界

事件驱动的 Agent 调度 OS：接入世界、规则派活、工单/Run、人审挂起、长任务与审计。不跑模型（S1），不出网（S6）。

---

## 2. 完整能力地图

| 能力簇 | 具体能力 | 对标 | 波次 |
| --- | --- | --- | --- |
| **事件接入** | HTTP 事件、Webhook 适配、轮询脚本回投、内部 emits | autoloop sources | W2 |
| **定时** | cron、补跑策略、时区、夏令时、错过只补最近 | autoloop cron | W2–W3 |
| **规则** | 匹配、版本、灰度、灰度流量、坏规则保旧版+告警、elsewhere 登记 | autoloop rules | W2–W4 |
| **事项/工单** | WorkItem；parallel by subject；队列合并；订阅扇出 | superagent Ticket/Thread | W2–W4 |
| **授权 Mandate** | 策略版本、范围、强度、撤销/重验 | superagent Mandate | W3–W4 |
| **Eval 准入** | 门禁+研判（可接 Runtime）决定是否接单 | superagent Eval | W4–W5 |
| **Run 生命周期** | claim/租约/心跳/取消/不确定态/迟到结果 | autoloop work | W2 |
| **回执** | ask/notice/resolve/later/events/usage；回执校验与重写 | autoloop result | W2 |
| **人审 Attention** | 选项/自由文本/超时升级/批量；Web+IM 同源 | autoloop cards | W2 |
| **通知** | tell、静默策略 needed/always/never | autoloop tell | W2 |
| **熔断** | hops TTL、连续失败停、预算熔断（对接配额） | autoloop hops | W2–W3 |
| **交付长任务** | 审查→CI→合并→发布→健康检查类流水线插件位 | autoloop delivery | W4–W5 |
| **审计** | why、事实流 SSE、导出 | autoloop why/stream | W2–W3 |
| **事件溯源** | 事实日志+投影重建+不变式测试 | autoloop kernel | W3–W5 |
| **多 Worker** | 多接单程序、容量、亲和 | autoloop workers | W3 |
| **SLA** | 超时策略、升级链、值班日历 | 企业 ITSM | W4–W5 |

---

## 3–5. 模型 / 组件 / 状态机

实体：Event、RuleVersion、WorkItem、Run、Attention、Mandate、Subscription、Receipt、Fact、DueTimer。  
包：event/rule/workitem/run/attention/mandate/eval/decide/evolve/why/delivery。  
状态机见前版并扩展：`WAITING_MANDATE_REVALIDATE`、`DELIVERY_PIPELINE`。

---

## 6–7. API / MQ

API：Event/Run/Attention/Rule/Mandate/Why/Delivery/Admin。  
MQ：`tioka-orch` 覆盖 event/run/attention/mandate/delivery/fact。

---

## 8–14. 数据·安全·依赖·失败·观测·测试·波次

数据含事实表与投影；安全含来源密钥与 answerer；依赖 runtime/system；测试含幂等/挂起/hops/坏规则/多 worker。  

| 波次 | 内容 |
| --- | --- |
| W2 | 事件规则 Run Attention 回执 IM 联动 |
| W3 | cron later why 溯源多 worker hops 预算 |
| W4 | Mandate Eval 订阅扇出 SLA |
| W5 | 交付流水线、不变式/强杀级验证 |

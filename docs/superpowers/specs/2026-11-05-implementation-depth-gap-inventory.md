# 实现深度缺口清单（Depth Gap Inventory）

> 日期：2026-11-05  
> 状态：**委员会定稿（用户授权代审，不参与人工走查）**  
> 评审：工程经理 · 架构 · 产品售前 · 质量门禁 → 全票 **APPROVE_WITH_CHANGES**（变更已吸收）  
> 权威对齐：[33](../../architecture/implementation/33-差距收口与后续推进计划.md) · [S1–S14](../../architecture/systems/) · [ADR-009](../../architecture/adr/ADR-009-persistence-mybatis-plus-base-do.md) · [ADR-014](../../architecture/adr/ADR-014-agentcore-alignment-package.md)  
> 实施计划：[2026-11-05-depth-p0-poc-fidelity.md](../plans/2026-11-05-depth-p0-poc-fidelity.md)

---

## 0. 一句话

**现状是「切片剧场 + 完成度标签通胀」**：API/页面在 mock 下能绿，相对 S 篇与 33 Exit，领域语义停在 D0–D1，却被叙述成 D2–D3。  
本清单**否决**将 33 §17「R2–R5 工程就绪 / 开 R6」当作事实；以代码行为为准。

**对外标准句（禁用「工程就绪 / 真 PoC Exit」除非人签）：**

> Tioka 当前是「可演示的垂直切片 + 可开关的保真路径」；不是已签字的商业 PoC Exit。任何「就绪」仅指工程切片可串通，禁止等同于 G1/W1 Exit。

---

## 1. 如何读本表

### 1.1 深度等级（Depth）

| 等级 | 含义 | 典型证据 |
| --- | --- | --- |
| **D0 空壳** | 目录/常量/标题 | `*MqTopics` 无消费者 |
| **D1 切片** | API 可串通；允许 mock/硬编码 | GateRegression mock 绿；`accept.sh` |
| **D2 保真路径** | 关 mock 后主路径符合设计；自动化不可 SKIP=PASS | fidelity 集成测 + 真依赖或 WireMock |
| **D3 Exit** | 不变量 + 失败态 + 观测 + **产品签字** | `accept-poc-exit` 人签且非 exit 2 |

「小学生作品」≈ **能力停在 D0–D1，对外按 D2–D3 讲**。

### 1.2 评分（相对对应波 Exit；审计用，**不得**据此开 R6）

| 分区 | 相对 Exit | 说明 |
| --- | ---: | --- |
| W0 / R1 | ~75–85% | JDBC 已清；护栏半成品仍在 |
| W1 / R2 | ~40–50% | 有非 mock 代码路径；默认仍 mock |
| W2 / R3 | ~35–45% | 卡片可点；规则三动作 |
| W3 / R4 | ~35–45% | 解析有；默认哈希向量 |
| W4 / R5 | ~40–50% | DAG 真；关键节点语义浅 |
| W5 / R6 | ~10–20% | 占位；**冻结** |
| 前端 HCI | ~45–55% | Part 有；工作台感弱 |
| 横切 | ~20–30% | 0 事务 / 无 MQ 消费 |
| **可卖 PoC** | **~40–50%** | 可 demo；不可 Exit 叙事 |

### 1.3 人力闸门（硬约束，执行计划必须抄写）

- **未过 W1/R2 产品 Exit**：P1/P2（R3/R4/R5 保真）主人力 ≤ 20%。  
- **禁止**开 R6 / W5 密度新壳。  
- **X-04/X-05/X-06/MCP-03** 永久 ≤ P3，**禁止回升 P0**（售前无感的工程洁癖）。  
- ToolGateway 完整收口 / Workdisk / Skill G-SK / SecretStore 白名单 → **M-Gap**，不偷渡进 R2 Exit 定义。

---

## 2. 根因分层

| 层 | 症状 | 主次 |
| --- | --- | --- |
| L1 勾选剧场 | 「工程就绪」≠ Exit | 必须先回写文档 |
| L2 工程半成品 | 假分层、物理删、基类未用 | **P3 辅线** |
| L3 **语义玩具化** | mock、blob、Grant 断链、假不可变 | **P0 主战场** |
| L4 体验空洞 | Chat 联调感、Observability dump | **P0 含最小 CH-01** |

---

## 3. Runtime / Chat（S1 / S3）

| ID | 设计要求 | 现状 | 深度 | 缺口 | 验收标准 | 波次 |
| --- | --- | --- | --- | --- | --- | --- |
| RT-01 | Session/Turn/Step 或事件可重建 | `partsJson` blob | D1 | 领域塌缩 | **非本轮 P0 全量**；可选最小「turnId 索引」spike | R2 后半 |
| RT-02 | `clientMsgId` 幂等到该 turn | 命中返回整会话 parts | D1 | 粒度错 | 同 key 返回该 turn；无重复副作用（单测） | **P0** |
| RT-03 | cancel 中断进行中流 | 仅标志位；仍可能产 delta | D1 | 假取消 | cancel 后不再持久化新 part（单测） | **P0** |
| RT-04 | fork/resume/跨进程 | 内存热表优先 | D0–D1 | 多实例 | 杀进程可恢复（非 P0） | R2–R3 |
| RT-05 | 真 SSE；禁假切块 | 有路径；默认 mock | D1 | 默认玩具 | 与 MD-01 捆绑：fidelity profile 可见增量；SKIP≠PASS | **P0** |
| RT-06 | 工具超时/并行/截断 | 简陋 + echo 兜底 | D1 | 边界态 | 非 P0 | R2–R3 |
| RT-07 | **Grant 快照闭合**：执行只用 AgentVersion.`mcpGrantIds`；禁 versionId 当 agentId；禁全局 `callToolByName`；禁 Gateway null/"" 旁路 | 四点均可复现 | D1 | 权限叙事崩 | 快照外工具 → 4xx；改 Grant 不影响已发布 version（单测） | **P0** |
| RT-11 | ToolGateway **唯一**入口 + ArchUnit（ADR-014 G-TG） | 与 RT-07 易混淆 | D1 | 入口未收口 | **M-Gap**；不进 R2 Exit | M-Gap |
| RT-08 | Workdisk / 多模态 | 未落地 | D0 | AgentCore | M-Gap | M-Gap |
| RT-09 | Turn/Tool/LLM span | 用量雏形 | D1 | 不可观测 | 非 P0 | R2–R3 |
| RT-10 | 同轮冻结 1 个 ModelBinding | 与路由揉在一起 | D1 | 同轮可漂 | 同 turn 工具续跑不换模（单测） | **P0** |
| RT-12 | WAITING_TOOL / APPROVAL 状态机 | 弱 | D0–D1 | 挂起续跑 | R3 / M-Gap HITL | R3 |
| CH-01 | 最小产品 Chat：多会话、错误气泡、非联调 IA | Part+联调页 | D1–D2 | 售前一眼假 | **走查清单 5 项勾选**（见计划） | **P0** |

---

## 4. Model / Agent / MCP

| ID | 设计要求 | 现状 | 深度 | 缺口 | 验收标准 | 波次 |
| --- | --- | --- | --- | --- | --- | --- |
| MD-01 | 自有 Key；可关 mock；无 key 显式失败 | 默认 `model.mock=true` | D1 | 默认回声 | fidelity：**禁止**无 key SKIP=0；无 key → 非 200 假成功 | **P0** |
| MD-02 | 限流/重试/难度路由非玩具 | 近 0；关键词路由 | D0–D1 | 韧性 | 非 P0 | R2–R3 |
| MD-03 | 绑定已发布 RoutingPlanVersion | 字段级 | D1 | 路由快照 | 执行只读 versionId | R2 |
| AG-01 | 发布后不可变 | 有 hash；仍 `updateById` | D1 | 护栏假 | 已发布 update → 拒绝（单测） | **P0** |
| AG-02 | Version 固化 grant/skill；驱动执行 | 字段可存；执行未严读 | D1 | 与 RT-07 同史诗 | 见 RT-07 | **P0** |
| AG-04 | Session **钉死** AgentVersion；不跟随最新 | 易漂 | D1 | 发布护栏可绕开 | 会话创建锁 versionId；中途改 Agent 不影响（单测） | **P0** |
| AG-03 | Skill 安装注入 G-SK | 有 SkillInstall；缺默认贯通 | D1 | 管理≠执行 | M-Gap | M-Gap |
| MCP-01 | 真 HTTP list/call；关 mock 无本地 echo 兜底 | 默认 mcp.mock；可 echo | D1 | 演示假工具 | WireMock/容器 E2E；mock=false 禁 echo fallback | **P0** |
| MCP-02 | allowlist + SSRF | 弱 | D1 | 安全 | 随 RT-07 起步；完整 R3 | P0 起步 |
| MCP-03 | 禁物理删挖护栏 | `physicalDeleteByServerId` | D1 | 护栏洞 | **P3 only** | P3 |

---

## 5. Orchestration / Knowledge / Flow（摘要；P0 不主攻）

| ID | 深度 | 备注 | 波次 |
| --- | --- | --- | --- |
| OR-01～05 | D0–D1 | 三动作 if；无 Runtime 派工；why 字符串 | R3（未过 R2 Exit ≤20% 人力） |
| OR-06 | D0 | Mandate/Eval/溯源冻结；**多 worker 争抢归 OR-02/R3，勿整包打成 R6** | 部分冻结 |
| KB-02/03 | D1 | HashEmbedding；`i+=200` | R4 |
| KB-07 | D0 | GraphRAG/Wiki **冻结** | 冻结 |
| FL-01 | D1–D2 | DAG 有；汇合边界弱 | R5 |
| FL-02 | D1 | Agent 节点未开 Runtime；HumanCard 有挂起标记但调度不中断下游 | R5 |

---

## 6. 前端 / 横切

| ID | 深度 | P0？ | 备注 |
| --- | --- | --- | --- |
| FE-01 | D2 已达标 | 否 | 不占缺口预算 |
| FE-02 | D2 缺快照 | 可选 | vitest 最小快照可并行 |
| FE-05 | D0–D1 | 否 | Observability dump；非 P0 |
| X-01 | D0 | **薄** | 仅 publish Agent +（可选）ingest 事务 |
| X-03 | D1 | **薄** | 仅演示写接口 DTO+`@Valid` |
| X-02 | D0 | 否 | MQ：实现或 ADR 延期 |
| X-04～06 | D1 | **禁止升 P0** | Store / WorkspaceAware / TenantContext 命名 |
| X-07 | D1 | 文档 | slice/fidelity/exit 分离 |
| X-07b | — | **P0** | 对外禁引用 slice 绿；材料只准 fidelity/exit 标签 |
| X-08 | D1–D2 | 否 | Secret local 回退 |
| X-09 | D0 | M-Gap | SecretStore.get 白名单 |

---

## 7. 委员会定稿的 P0（唯一主路径）

**成功标准（售前可说的话上限）：**  
「关玩具默认 + 真 Key/真 MCP（或 WireMock）冒烟绿 + Grant 越权 4xx + 取消后不再产 part + 同轮不换模 + 发布不可变 + Session 钉版本 + Chat 最小走查勾选 + §17 话术已降级。」  
**仍不等于** R2 产品 Exit 人签。

| 序 | 项 | 说明 |
| ---: | --- | --- |
| 0 | **DOC-33-17** + X-07b | 先降级话术；删「开 R6」 |
| 1 | AG-01 | 已发布拒绝 update |
| 2 | AG-04 + RT-07 + AG-02 | 钉版本 + Grant 快照闭合（含旁路修复） |
| 3 | MD-01 + RT-05 + MCP-01 | fidelity profile；SKIP≠PASS；禁 echo fallback |
| 4 | RT-02 + RT-03 + RT-10 | 幂等 / cancel / 同轮冻结 |
| 5 | CH-01 | 最小产品 Chat 走查 5 项 |
| 6 | X-01/X-03 薄切片 | 发布事务 + 演示 API 校验 |
| — | **RT-01 全量 Turn 表** | **移出 P0**（可选后半 spike） |
| — | RT-11 / RT-08 / AG-03 / X-09 | **M-Gap** |
| — | X-04 等 | **P3** |

### P0 最小门禁（质量委员会；计划必须落地）

1. fidelity 集成测：`mock=false` + WireMock/fixture；无 key **失败**非 SKIP=0  
2. 幂等 + cancel 行为测  
3. Grant 快照 + 越权 4xx  
4. 已发布不可变拒改  
5. （薄）发布事务回滚或校验 400  

---

## 8. 与 33 §17 冲突（已授权回写）

| 原表述 | 定稿 |
| --- | --- |
| R2–R5「工程就绪」 | **slice + 部分 fidelity 路径；EXIT 未签** |
| 「下一动作：开 R6」 | **禁止**；下一动作 = 执行本清单 P0 |
| ENGINEERING READY | 对内可保留工程半段标签；**对外禁用**等同 Exit |

---

## 9. 评审记录

| 维度 | Agent | 裁决 |
| --- | --- | --- |
| 工程经理 | [工程经理评审](eb939e65-8a7b-4ae0-ad6c-e243b24968c0) | APPROVE_WITH_CHANGES（抽查 6 处属实；缩 P0；写死 RT-07） |
| 架构 | [架构评审](a7b9ae45-232e-453d-bfcf-f188da65bd02) | APPROVE_WITH_CHANGES（补 AG-04/RT-10；拆 RT-11→M-Gap） |
| 产品售前 | [产品评审](e0fcfacd-0852-4d99-a3d9-5af221ec4839) | APPROVE_WITH_CHANGES（升 CH-01；X-04 禁升 P0；禁用词） |
| 质量门禁 | [质量评审](c68de18a-5e61-4586-80d4-590544764dd9) | APPROVE_WITH_CHANGES（门禁≈D1；补 5 条最小门禁） |

**综合裁决：APPROVE_WITH_CHANGES → 变更已并入本文 → 视为定稿。**  
用户明确不参与个人审阅；分歧已在委员会内收敛（无升级用户决策的分歧）。

---

## 10. 明确不做

- 推倒模块树；引入行级 `tenantId`  
- 为勾选新增空壳 API；开 R6  
- 未完成 P0 门禁即宣称真 PoC Exit  

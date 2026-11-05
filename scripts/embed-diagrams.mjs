#!/usr/bin/env node
/**
 * 向各 Markdown 注入「图形」章节：
 * 1) PNG（push 时上传到飞书，文档内可见）
 * 2) 文本绘图（纯字符，飞书一定可见）
 * 3) draw.io 源链接（本地可编辑）
 * 幂等：已有 <!-- DIAGRAMS:BEGIN --> 则替换块。
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DOC = join(ROOT, "feishu-docs/Tioka");

const BEGIN = "<!-- DIAGRAMS:BEGIN -->";
const END = "<!-- DIAGRAMS:END -->";

function relBase(fromFile) {
  const depth = fromFile.split("/").length - 1;
  return depth <= 0 ? "diagrams/" : "../".repeat(depth) + "diagrams/";
}

/** 文本绘图（飞书代码块外也能看；用等宽预格式） */
const ASCII = {
  "doc-hierarchy": `
Tioka
├── 01-管理文档
├── 02-企业架构          BA / TA / DA / AA / EA / FE
├── 03-能力域详设
│   ├── 总览与基座       S0 / S8 / S9
│   ├── 执行与编排       S1 / S2
│   ├── 产品与交互       S3 / S4 / S5 / S7
│   ├── 连接器           S6
│   └── 模型与资产       S10–S14
└── 04-实施交付          30 / 31 / 32
`.trim(),
  "ta-c4-context": `
                 [ 用户/审批/管理员 ]
                          |
          +---------------+---------------+
          |               |               |
     [ 客户 IdP ]    [ Tioka 平台 ]   [ MCP Server ]
          |               |               |
                     +----+----+
                     |         |
              [ 模型供应商 ]  [ IM/业务/中间件 ]
`.trim(),
  "ta-layers": `
┌─────────────────────────────────────┐
│ 体验层 FE：Chat / Studio / 配置 / 审批 │
├─────────────────────────────────────┤
│ 接入层：REST / SSE / Webhook / MCP    │
├─────────────────────────────────────┤
│ 应用服务层：*-biz 用例                │
├─────────────────────────────────────┤
│ 领域层：聚合 / 策略                   │
├─────────────────────────────────────┤
│ 基础设施：DB / MQ / OSS / 向量 / SPI  │
└─────────────────────────────────────┘
`.trim(),
  "ta-runtime-path": `
UI SSE → Chat → Runtime Turn Loop → ToolRegistry → MCP/KB/Flow
                 ↑                      |
            ModelRouter            Attention(挂起)
`.trim(),
  "s0-lifecycle": `
刺激(用户/事件/IM) → Chat|Orch|Flow → Runtime Session
                         → Tools(KB/Flow/MCP)
                         → Part/Attention
                         → 写回 Chat/IM/业务/知识
`.trim(),
  "s0-asset-mount": `
Agent(S11)
  ├── RoutingPlan(S10)
  ├── McpGrant(S12)
  ├── SkillBinding(S13)
  └── FlowPublication(S14)
         └── Runtime 只读已发布快照
`.trim(),
  "impl-waves": `
W0 契约骨架 → W1 PoC Gate → W2 Orch+IM
                 ├─→ W3 Knowledge ─┐
                 └─→ W4 Flow ──────┴→ W5 密度/HA → G1–G7
`.trim(),
  "ba-poc-sequence": `
客户IT ──SSO/中间件──┐
管理员 ──模型+MCP────┼──→ Tioka ──演示达标──→ PoC Gate
工程师 ──发布Agent───┤
用户   ──对话+工具───┘
`.trim(),
  "ba-capability-map": `
              [ Tioka 业务价值 ]
     ┌────┬────┬────┬────┬────┐
   身份  对话  执行  模型  知识  流程
          │              │
        人审            连接/治理
`.trim(),
  "s1-turn-loop": `
append/steer → 选模 → 模型流式 → 有工具? ──是──→ 调工具 ──┐
                              │否              │结果回灌│
                              ▼              └────────┘
                           Turn完成 ←── Attention续跑(suspend)
`.trim(),
  "ea-module-deps": `
framework ← system-api ← model / runtime / agent / mcp
                ↑
     chat / orch / flow / knowledge / connector  (只依赖 *-api)
`.trim(),
  "aa-app-interaction": `
tioka-ui ──REST/SSE──→ chat / agent / orch
                              │
                         runtime-api ←── flow
                              │
                    model-api + connector(出网)
`.trim(),
  "fe-dual-card": `
[interactive_card · continue]     [approval_card · suspend]
     对话内继续、不挂起 Run              挂起 Attention / 审批台
   --card-continue-accent            --card-suspend-accent
`.trim(),
  "da-concept-er": `
Workspace → Agent → Conversation → Session
              ├── RoutingPlan / McpGrant / Skill / Flow
              └── Session → Orch + Knowledge
`.trim(),
  "da-data-flow": `
User → ch.* → Turn/Part/Usage
配置发布 → MQ → Cache（运行时只读快照）
`.trim(),
  "fe-ia-nav": `
Shell
├── Chat / Approval / Obs / KB
├── Studio
└── Cfg → Agent / Model / MCP / Skill
`.trim(),
  "fe-part-renderer": `
SSE → PartRenderer → Text | Tool | Card | Citation | Fallback
`.trim(),
  "s2-orch-flow": `
Event → Rule → WorkItem → Run ⇄ Attention
                      └── Why → Done
`.trim(),
  "s3-chat-flow": `
User → Conv → Agent → Runtime → SSE → Store
`.trim(),
  "s4-knowledge-pipeline": `
Src → OSS → Parse → Vec → Retrieve → Citation
`.trim(),
  "s5-flow-publish": `
Draft → Version → API | Tool | MCP
              Tool → Agent → Run
`.trim(),
  "s6-connector": `
SPI → IM / Biz / Doc / MCP → 出网边界
`.trim(),
  "s7-hci-surfaces": `
Part → Chat | Approval | KB | Studio | Config | Obs
`.trim(),
  "s9-foundation": `
IdP + Org → Foundation → Secret / Audit / Adapter → Domains
`.trim(),
  "s10-routing": `
Provider → LogicalModel → Binding → Plan → Runtime(Failover)
`.trim(),
  "s11-agent-bindings": `
Draft → Version → Route/MCP/Skill/Flow → Runtime
`.trim(),
  "s12-mcp": `
Ext → Reg → Disc → Grant → Runtime
Outbound → ExtClient
`.trim(),
  "s13-skill-lifecycle": `
Import → Scan → Ready → Pub → Bind → Runtime
`.trim(),
  "s14-flow-mgmt": `
Project → Draft → Version → Pub → Ops / Engine
`.trim(),
  "mgmt-api-mq": `
Chat/Runtime ──同步 api──┐
Orch/IM / 资产发布 / 知识 ──异步 MQ─┘
`.trim(),
};

function asciiFor(name) {
  return (
    ASCII[name] ||
    `${name}\n(详见 PNG / draw.io；结构见 DIAGRAM-INDEX)`
  );
}

function block(fromFile, items) {
  const base = relBase(fromFile);
  const lines = [BEGIN, "", "## 图形（飞书可见：PNG + 文本绘图）", ""];
  lines.push(
    "> **飞书正文**：下方 **PNG 图**（push 时自动上传）+ **文本绘图**（等宽字符，一定可见）。",
    "> **本地可编辑**：`.drawio` 源文件；不要依赖 Mermaid 代码块在飞书里渲染（目前不可见）。",
    "",
  );
  for (const it of items) {
    const png = `${base}exports/${it.file}.png`;
    const dio = `${base}${it.file}.drawio`;
    lines.push(`### ${it.title}`, "");
    lines.push(`![${it.title}](${png})`, "");
    lines.push("文本绘图：", "");
    lines.push("```", asciiFor(it.file), "```", "");
    lines.push(`- 可编辑源：[${it.file}.drawio](${dio})`, "");
  }
  lines.push(END, "");
  return lines.join("\n");
}

function upsert(fileRel, items) {
  const path = join(DOC, fileRel);
  if (!existsSync(path)) {
    console.warn("skip missing", fileRel);
    return;
  }
  let md = readFileSync(path, "utf8");
  const section = block(fileRel, items);
  if (md.includes(BEGIN) && md.includes(END)) {
    md = md.replace(new RegExp(`${BEGIN}[\\s\\S]*?${END}\\n?`), section);
  } else {
    // 插在首个 ## 之后或文末
    const m = md.match(/^# .+\n/);
    if (m) {
      const rest = md.slice(m[0].length);
      md = m[0] + "\n" + section + rest.replace(/^\n+/, "\n");
    } else {
      md = section + md;
    }
  }
  writeFileSync(path, md);
  console.log("embedded", fileRel);
}

const embeds = [
  {
    file: "00-项目主页.md",
    items: [
      {
        title: "文档层级树",
        file: "doc-hierarchy",
        mermaid: `flowchart TB
  root[Tioka]
  root --> g1[01管理文档]
  root --> g2[02企业架构]
  root --> g3[03能力域详设]
  root --> g4[04实施交付]
  g3 --> s1[总览与基座]
  g3 --> s2[执行与编排]
  g3 --> s3[产品与交互]
  g3 --> s4[连接器]
  g3 --> s5[模型与资产]`,
      },
    ],
  },
  {
    file: "01-管理文档/03-接口与数据总表.md",
    items: [
      {
        title: "同步 API 与异步 MQ",
        file: "mgmt-api-mq",
        mermaid: `flowchart LR
  chat[Chat_Runtime] --> api[同步api]
  orch[Orch_IM] --> mq[异步MQ]
  asset[资产发布] --> mq
  kb[知识同步] --> mq
  api -.-> mq`,
      },
    ],
  },
  {
    file: "02-企业架构/40-BA-业务架构.md",
    items: [
      {
        title: "业务能力地图",
        file: "ba-capability-map",
        mermaid: `flowchart TB
  V[Tioka业务价值]
  V --> BC1[身份组织]
  V --> BC2[对话助手]
  V --> BC3[执行工具]
  V --> BC4[模型成本]
  V --> BC5[知识]
  V --> BC6[流程]
  BC2 --> BC7[事件人审]
  BC3 --> BC8[连接集成]`,
      },
      {
        title: "PoC Gate 路径",
        file: "ba-poc-sequence",
        mermaid: `flowchart LR
  IT[客户IT] --> T[Tioka]
  Admin[管理员] --> T
  Eng[工程师] --> T
  User[用户] --> T
  T --> Gate[PoC_Gate]`,
      },
    ],
  },
  {
    file: "02-企业架构/41-TA-技术架构.md",
    items: [
      {
        title: "C4 Context",
        file: "ta-c4-context",
        mermaid: `flowchart TB
  U[用户] --> T[Tioka]
  IdP[客户IdP] --> T
  T --> LLM[模型供应商]
  T --> MCP[MCP_Server]
  T --> IM[IM]
  Biz[业务系统] --> T
  T --> Infra[PG_Redis_MQ]`,
      },
      {
        title: "逻辑分层",
        file: "ta-layers",
        mermaid: `flowchart TB
  L1[体验层FE] --> L2[接入层]
  L2 --> L3[应用服务层]
  L3 --> L4[领域层]
  L4 --> L5[基础设施]`,
      },
      {
        title: "运行时关键路径",
        file: "ta-runtime-path",
        mermaid: `flowchart LR
  UI --> Chat --> RT[Runtime]
  MR[ModelRouter] --> RT
  RT --> TR[ToolRegistry] --> Tools[MCP_KB_Flow]
  RT --> Att[Attention]`,
      },
    ],
  },
  {
    file: "02-企业架构/42-DA-数据架构.md",
    items: [
      {
        title: "概念模型",
        file: "da-concept-er",
        mermaid: `flowchart TB
  WS[Workspace] --> AG[Agent]
  AG --> RP[RoutingPlan]
  AG --> MCP[McpGrant]
  AG --> SK[Skill]
  AG --> FL[Flow]
  AG --> CV[Conversation]
  CV --> SS[Session]
  SS --> OR[Orch]
  SS --> KB[Knowledge]`,
      },
      {
        title: "对话写路径与发布流",
        file: "da-data-flow",
        mermaid: `flowchart LR
  U[User] --> CH[ch] --> RT[Turn] --> Part --> Usage
  Pub[配置发布] --> MQ --> Cache`,
      },
    ],
  },
  {
    file: "02-企业架构/43-AA-应用与集成架构.md",
    items: [
      {
        title: "应用交互总图",
        file: "aa-app-interaction",
        mermaid: `flowchart TB
  UI[tioka-ui] --> Chat
  UI --> Agent
  UI --> Orch
  Chat --> RT[runtime]
  Agent --> RT
  Orch --> RT
  Flow --> RT
  RT --> Model
  RT --> Conn[connector]`,
      },
    ],
  },
  {
    file: "02-企业架构/44-EA-Java工程架构.md",
    items: [
      {
        title: "模块 api 依赖",
        file: "ea-module-deps",
        mermaid: `flowchart TB
  sys --> fw[framework]
  runtime --> sys
  runtime --> model
  agent --> model
  agent --> mcp
  chat --> runtime
  chat --> agent
  flow --> runtime
  knowledge --> connector`,
      },
    ],
  },
  {
    file: "02-企业架构/45-FE-前端架构.md",
    items: [
      {
        title: "信息架构导航",
        file: "fe-ia-nav",
        mermaid: `flowchart TB
  Shell --> Chat
  Shell --> Approval
  Shell --> Obs
  Shell --> KB
  Shell --> Studio
  Shell --> Cfg
  Cfg --> Agent
  Cfg --> Model
  Cfg --> MCP
  Cfg --> Skill`,
      },
      {
        title: "Part 渲染体系",
        file: "fe-part-renderer",
        mermaid: `flowchart LR
  SSE --> PR[PartRenderer]
  PR --> Text
  PR --> Tool
  PR --> Card
  PR --> Citation
  PR --> Fallback`,
      },
      {
        title: "双卡片可感知差异",
        file: "fe-dual-card",
        mermaid: `flowchart LR
  Cont[interactive_card_continue] --> Tok1[continue_accent]
  Susp[approval_card_suspend] --> Tok2[suspend_accent]`,
      },
    ],
  },
  {
    file: "03-能力域详设/总览与基座/10-S0-体系总览与边界.md",
    items: [
      {
        title: "系统级生命周期",
        file: "s0-lifecycle",
        mermaid: `flowchart LR
  Stim[刺激] --> Entry[Chat_Orch_Flow] --> RT[Runtime]
  RT --> Tools
  RT --> HCI[Part_Attention]
  Tools --> Out[写回]
  HCI --> Out`,
      },
      {
        title: "资产挂载关系",
        file: "s0-asset-mount",
        mermaid: `flowchart TB
  AG[Agent] --> RP[RoutingPlan]
  AG --> MCP
  AG --> Skill
  AG --> Flow
  RP --> RT[Runtime快照]
  MCP --> RT
  Skill --> RT
  Flow --> RT`,
      },
    ],
  },
  {
    file: "03-能力域详设/总览与基座/18-S8-工程部署与依赖.md",
    items: [
      {
        title: "模块依赖（工程视角）",
        file: "ea-module-deps",
        mermaid: `flowchart TB
  server[tioka-server] --> modules
  modules --> fw[framework]`,
      },
    ],
  },
  {
    file: "03-能力域详设/总览与基座/19-S9-平台基座与企业接入.md",
    items: [
      {
        title: "Foundation 与 SPI",
        file: "s9-foundation",
        mermaid: `flowchart TB
  IdP --> Core[Foundation]
  Org --> Core
  Core --> Secret
  Core --> Audit
  Core --> Adapter
  Core --> Domains`,
      },
    ],
  },
  {
    file: "03-能力域详设/执行与编排/11-S1-Runtime执行内核.md",
    items: [
      {
        title: "Turn Loop",
        file: "s1-turn-loop",
        mermaid: `flowchart LR
  In --> Plan[选模] --> LLM --> Tool{工具?}
  Tool -->|结果| LLM
  Tool -->|suspend| Att[Attention]
  Att -->|续跑| LLM
  Tool -->|完成| Done`,
      },
    ],
  },
  {
    file: "03-能力域详设/执行与编排/12-S2-Orchestration编排.md",
    items: [
      {
        title: "编排主链",
        file: "s2-orch-flow",
        mermaid: `flowchart LR
  Event --> Rule --> WI[WorkItem] --> Run
  Run --> Att[Attention]
  Run --> Why
  Att --> Run
  Run --> Done`,
      },
    ],
  },
  {
    file: "03-能力域详设/产品与交互/13-S3-Chat对话域.md",
    items: [
      {
        title: "对话触发执行",
        file: "s3-chat-flow",
        mermaid: `flowchart LR
  User --> Conv --> Agent --> RT --> SSE --> Store`,
      },
    ],
  },
  {
    file: "03-能力域详设/产品与交互/14-S4-Knowledge知识域.md",
    items: [
      {
        title: "入库与引用",
        file: "s4-knowledge-pipeline",
        mermaid: `flowchart LR
  Src --> OSS --> Parse --> Vec --> Ret --> Cit`,
      },
    ],
  },
  {
    file: "03-能力域详设/产品与交互/15-S5-Flow工作流.md",
    items: [
      {
        title: "发布与调用",
        file: "s5-flow-publish",
        mermaid: `flowchart LR
  Draft --> Ver
  Ver --> API
  Ver --> Tool
  Ver --> MCP
  Tool --> Agent --> Run`,
      },
    ],
  },
  {
    file: "03-能力域详设/产品与交互/17-S7-HCI人机交互.md",
    items: [
      {
        title: "交互面与 Part",
        file: "s7-hci-surfaces",
        mermaid: `flowchart TB
  Part --> Chat
  Part --> Approval
  Part --> KB
  Part --> Studio
  Part --> Config
  Part --> Obs`,
      },
    ],
  },
  {
    file: "03-能力域详设/连接器/16-S6-Connector连接器.md",
    items: [
      {
        title: "连接器出网边界",
        file: "s6-connector",
        mermaid: `flowchart TB
  SPI --> IM
  SPI --> Biz
  IM --> Out[出网边界]
  Biz --> Out
  Doc --> Out
  MCP --> Out`,
      },
    ],
  },
  {
    file: "03-能力域详设/模型与资产控制面/20-S10-模型管理与智能路由.md",
    items: [
      {
        title: "模型路由链路",
        file: "s10-routing",
        mermaid: `flowchart LR
  Provider --> LogicalModel --> Binding --> Plan
  Decision --> Plan --> Runtime --> Failover`,
      },
    ],
  },
  {
    file: "03-能力域详设/模型与资产控制面/21-S11-Agent资产管理.md",
    items: [
      {
        title: "Agent 发布与挂载",
        file: "s11-agent-bindings",
        mermaid: `flowchart LR
  Draft --> Version
  Version --> Route
  Version --> MCP
  Version --> Skill
  Version --> Flow
  Version --> Runtime`,
      },
    ],
  },
  {
    file: "03-能力域详设/模型与资产控制面/22-S12-MCP管理.md",
    items: [
      {
        title: "MCP Client/Server",
        file: "s12-mcp",
        mermaid: `flowchart LR
  Ext --> Reg --> Disc --> Grant --> RT
  Outbound --> ExtClient`,
      },
    ],
  },
  {
    file: "03-能力域详设/模型与资产控制面/23-S13-Skill资产管理.md",
    items: [
      {
        title: "Skill 生命周期",
        file: "s13-skill-lifecycle",
        mermaid: `flowchart LR
  Import --> Scan --> Ready --> Pub --> Bind --> Runtime`,
      },
    ],
  },
  {
    file: "03-能力域详设/模型与资产控制面/24-S14-工作流编排管理.md",
    items: [
      {
        title: "Flow 管理控制面",
        file: "s14-flow-mgmt",
        mermaid: `flowchart LR
  Project --> Draft --> Version --> Pub --> Ops
  Pub --> Engine`,
      },
    ],
  },
  {
    file: "04-实施交付/30-全局实施方案.md",
    items: [
      {
        title: "交付波次",
        file: "impl-waves",
        mermaid: `flowchart LR
  W0 --> W1[W1_PoC] --> W2
  W2 --> W3
  W2 --> W4
  W3 --> W5
  W4 --> W5
  W5 --> G[G1_G7]`,
      },
      {
        title: "运行时关键路径",
        file: "ta-runtime-path",
        mermaid: `flowchart LR
  UI --> Chat --> RT
  MR --> RT --> Tools`,
      },
    ],
  },
  {
    file: "04-实施交付/31-W0至W5实施计划.md",
    items: [
      {
        title: "波次与并行",
        file: "impl-waves",
        mermaid: `flowchart LR
  W0 --> W1 --> W2
  W2 --> W3
  W2 --> W4
  W3 --> W5
  W4 --> W5`,
      },
    ],
  },
  {
    file: "01-管理文档/01-需求文档.md",
    items: [
      {
        title: "角色与价值（能力鸟瞰）",
        file: "ba-capability-map",
        mermaid: `flowchart TB
  V[价值] --> Chat
  V --> Knowledge
  V --> Flow
  V --> Orch
  V --> Foundation`,
      },
    ],
  },
  {
    file: "01-管理文档/02-技术方案总览.md",
    items: [
      {
        title: "文档层级",
        file: "doc-hierarchy",
        mermaid: `flowchart TB
  root --> mgmt[01]
  root --> ent[02]
  root --> sys[03]
  root --> impl[04]`,
      },
      {
        title: "C4 Context",
        file: "ta-c4-context",
        mermaid: `flowchart TB
  U --> T[Tioka]
  IdP --> T
  T --> Ext[外部系统]`,
      },
    ],
  },
  {
    file: "01-管理文档/05-发布与运维.md",
    items: [
      {
        title: "波次与门禁",
        file: "impl-waves",
        mermaid: `flowchart LR
  W0 --> W1 --> W2 --> W5 --> Gates`,
      },
    ],
  },
];

for (const e of embeds) upsert(e.file, e.items);
console.log("done", embeds.length, "docs");

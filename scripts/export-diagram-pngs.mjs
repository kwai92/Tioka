#!/usr/bin/env node
/**
 * 从 embed 规格导出 Mermaid → PNG（飞书可见图）
 * 依赖：npx @mermaid-js/mermaid-cli
 */
import { writeFileSync, mkdirSync, readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "feishu-docs/Tioka/diagrams/exports");
const TMP = join(ROOT, "feishu-docs/Tioka/diagrams/.tmp-mmd");

const THEME = `%%{init: {'theme':'base','themeVariables':{'primaryColor':'#EEF6FF','primaryTextColor':'#0F172A','primaryBorderColor':'#2563EB','lineColor':'#64748B','secondaryColor':'#ECFDF5','tertiaryColor':'#FFF7ED','fontFamily':'Helvetica'}}}%%`;

// 与 embed-diagrams 同源的核心图（去重 by file key）
const DIAGRAMS = {
  "doc-hierarchy": `flowchart TB
  root[Tioka文档]
  root --> g1[01管理文档]
  root --> g2[02企业架构]
  root --> g3[03能力域详设]
  root --> g4[04实施交付]
  g3 --> s1[总览与基座]
  g3 --> s2[执行与编排]
  g3 --> s3[产品与交互]
  g3 --> s4[连接器]
  g3 --> s5[模型与资产]`,
  "ba-capability-map": `flowchart TB
  V[Tioka业务价值]
  V --> BC1[身份组织]
  V --> BC2[对话助手]
  V --> BC3[执行工具]
  V --> BC4[模型成本]
  V --> BC5[知识]
  V --> BC6[流程]
  BC2 --> BC7[事件人审]
  BC3 --> BC8[连接集成]`,
  "ba-poc-sequence": `flowchart LR
  IT[客户IT] --> T[Tioka]
  Admin[管理员] --> T
  Eng[工程师] --> T
  User[用户] --> T
  T --> Gate[PoC_Gate]`,
  "ta-c4-context": `flowchart TB
  U[用户] --> T[Tioka平台]
  IdP[客户IdP] --> T
  T --> LLM[模型供应商]
  T --> MCP[MCP_Server]
  T --> IM[IM]
  Biz[业务系统] --> T
  T --> Infra[PG_Redis_MQ_OSS]`,
  "ta-layers": `flowchart TB
  L1[体验层FE] --> L2[接入层]
  L2 --> L3[应用服务层]
  L3 --> L4[领域层]
  L4 --> L5[基础设施]`,
  "ta-runtime-path": `flowchart LR
  UI --> Chat --> RT[Runtime]
  MR[ModelRouter] --> RT
  RT --> TR[ToolRegistry] --> Tools[MCP_KB_Flow]
  RT --> Att[Attention]`,
  "da-concept-er": `flowchart TB
  WS[Workspace] --> AG[Agent]
  AG --> RP[RoutingPlan]
  AG --> MCP[McpGrant]
  AG --> SK[Skill]
  AG --> FL[Flow]
  AG --> CV[Conversation]
  CV --> SS[Session]
  SS --> OR[Orch]
  SS --> KB[Knowledge]`,
  "da-data-flow": `flowchart LR
  U[User] --> CH[ch] --> RT[Turn] --> Part --> Usage
  Pub[配置发布] --> MQ --> Cache`,
  "aa-app-interaction": `flowchart TB
  UI[tioka-ui] --> Chat
  UI --> Agent
  UI --> Orch
  Chat --> RT[runtime]
  Agent --> RT
  Orch --> RT
  Flow --> RT
  RT --> Model
  RT --> Conn[connector]`,
  "ea-module-deps": `flowchart TB
  sys --> fw[framework]
  runtime --> sys
  runtime --> model
  agent --> model
  agent --> mcp
  chat --> runtime
  chat --> agent
  flow --> runtime
  knowledge --> connector`,
  "fe-ia-nav": `flowchart TB
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
  "fe-part-renderer": `flowchart LR
  SSE --> PR[PartRenderer]
  PR --> Text
  PR --> Tool
  PR --> Card
  PR --> Citation
  PR --> Fallback`,
  "fe-dual-card": `flowchart LR
  Cont[interactive_card_continue] --> Tok1[continue_accent]
  Susp[approval_card_suspend] --> Tok2[suspend_accent]`,
  "s0-lifecycle": `flowchart LR
  Stim[刺激] --> Entry[Chat_Orch_Flow] --> RT[Runtime]
  RT --> Tools
  RT --> HCI[Part_Attention]
  Tools --> Out[写回]
  HCI --> Out`,
  "s0-asset-mount": `flowchart TB
  AG[Agent] --> RP[RoutingPlan]
  AG --> MCP
  AG --> Skill
  AG --> Flow
  RP --> RT[Runtime快照]
  MCP --> RT
  Skill --> RT
  Flow --> RT`,
  "s1-turn-loop": `flowchart LR
  In --> Plan[选模] --> LLM --> Tool{工具?}
  Tool -->|结果| LLM
  Tool -->|suspend| Att[Attention]
  Att -->|续跑| LLM
  Tool -->|完成| Done`,
  "s2-orch-flow": `flowchart LR
  Event --> Rule --> WI[WorkItem] --> Run
  Run --> Att[Attention]
  Run --> Why
  Att --> Run
  Run --> Done`,
  "s3-chat-flow": `flowchart LR
  User --> Conv --> Agent --> RT --> SSE --> Store`,
  "s4-knowledge-pipeline": `flowchart LR
  Src --> OSS --> Parse --> Vec --> Ret --> Cit`,
  "s5-flow-publish": `flowchart LR
  Draft --> Ver
  Ver --> API
  Ver --> Tool
  Ver --> MCP
  Tool --> Agent --> Run`,
  "s6-connector": `flowchart TB
  SPI --> IM
  SPI --> Biz
  IM --> Out[出网边界]
  Biz --> Out
  Doc --> Out
  MCP --> Out`,
  "s7-hci-surfaces": `flowchart TB
  Part --> Chat
  Part --> Approval
  Part --> KB
  Part --> Studio
  Part --> Config
  Part --> Obs`,
  "s9-foundation": `flowchart TB
  IdP --> Core[Foundation]
  Org --> Core
  Core --> Secret
  Core --> Audit
  Core --> Adapter
  Core --> Domains`,
  "s10-routing": `flowchart LR
  Provider --> LogicalModel --> Binding --> Plan
  Decision --> Plan --> Runtime --> Failover`,
  "s11-agent-bindings": `flowchart LR
  Draft --> Version
  Version --> Route
  Version --> MCP
  Version --> Skill
  Version --> Flow
  Version --> Runtime`,
  "s12-mcp": `flowchart LR
  Ext --> Reg --> Disc --> Grant --> RT
  Outbound --> ExtClient`,
  "s13-skill-lifecycle": `flowchart LR
  Import --> Scan --> Ready --> Pub --> Bind --> Runtime`,
  "s14-flow-mgmt": `flowchart LR
  Project --> Draft --> Version --> Pub --> Ops
  Pub --> Engine`,
  "impl-waves": `flowchart LR
  W0 --> W1[W1_PoC] --> W2
  W2 --> W3
  W2 --> W4
  W3 --> W5
  W4 --> W5
  W5 --> G[G1_G7]`,
  "mgmt-api-mq": `flowchart LR
  chat[Chat_Runtime] --> api[同步api]
  orch[Orch_IM] --> mq[异步MQ]
  asset[资产发布] --> mq
  kb[知识同步] --> mq
  api -.-> mq`,
};

mkdirSync(OUT, { recursive: true });
mkdirSync(TMP, { recursive: true });

let ok = 0;
let fail = 0;
for (const [name, body] of Object.entries(DIAGRAMS)) {
  const mmd = join(TMP, `${name}.mmd`);
  const png = join(OUT, `${name}.png`);
  writeFileSync(mmd, `${THEME}\n${body}\n`);
  const r = spawnSync(
    "npx",
    ["-y", "@mermaid-js/mermaid-cli@11.4.0", "-i", mmd, "-o", png, "-b", "white", "-s", "2"],
    { encoding: "utf8", timeout: 120000 },
  );
  if (r.status === 0 && existsSync(png)) {
    ok++;
    console.log("png", name, `${(readFileSync(png).length / 1024).toFixed(1)}KB`);
  } else {
    fail++;
    console.error("FAIL", name, r.stderr?.slice(-400) || r.stdout?.slice(-400));
  }
}
console.log(`\nDone: ${ok} ok, ${fail} fail → ${OUT}`);
if (fail) process.exit(1);

#!/usr/bin/env node
/**
 * 从简单 JSON 规格生成 diagrams.net (.drawio) 文件。
 * 用法: node scripts/gen-drawio.mjs
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "feishu-docs/Tioka/diagrams");

/**
 * 视觉体系：石板蓝 + 松绿 + 琥珀（避免紫光 AI 八股）
 * 节点带渐变/阴影/圆角；连线正交圆角 + 标签底色。
 */
const BASE =
  "rounded=1;arcSize=12;whiteSpace=wrap;html=1;shadow=1;fontFamily=Helvetica;fontSize=12;fontColor=#0F172A;align=center;verticalAlign=middle;spacing=8;strokeWidth=1.5;";

const STYLE = {
  // 控制面 / 平台
  box:
    BASE +
    "fillColor=#F0F7FF;gradientColor=#C7DFFF;gradientDirection=north;strokeColor=#3B82F6;",
  ctrl:
    BASE +
    "fillColor=#EEF6FF;gradientColor=#93C5FD;gradientDirection=north;strokeColor=#2563EB;fontStyle=1;",
  // 执行 / 产品
  exec:
    BASE +
    "fillColor=#ECFDF5;gradientColor=#6EE7B7;gradientDirection=north;strokeColor=#059669;fontStyle=1;",
  // 外部系统
  ext:
    BASE +
    "shape=hexagon;perimeter=hexagonPerimeter2;fillColor=#FFF7ED;gradientColor=#FDBA74;gradientDirection=north;strokeColor=#EA580C;",
  // 人 / 审批
  human:
    BASE +
    "shape=ellipse;fillColor=#FDF4FF;gradientColor=#E9D5FF;gradientDirection=north;strokeColor=#7C3AED;fontStyle=1;",
  // 数据
  data:
    BASE +
    "shape=cylinder3;size=10;fillColor=#F8FAFC;gradientColor=#CBD5E1;gradientDirection=north;strokeColor=#475569;",
  // 决策
  decision:
    BASE +
    "rhombus;fillColor=#FEF3C7;gradientColor=#FCD34D;gradientDirection=north;strokeColor=#D97706;fontStyle=1;",
  // 标题徽章
  title:
    "rounded=1;arcSize=20;whiteSpace=wrap;html=1;shadow=1;fontFamily=Helvetica;fontSize=16;fontStyle=1;fontColor=#FFFFFF;fillColor=#0F172A;gradientColor=#334155;gradientDirection=east;strokeColor=none;align=center;verticalAlign=middle;spacing=10;",
  // 画布底衬
  canvas:
    "rounded=1;arcSize=8;whiteSpace=wrap;html=1;fillColor=#F8FAFC;strokeColor=#E2E8F0;dashed=1;dashPattern=8 8;shadow=0;fontColor=#94A3B8;fontSize=10;align=left;verticalAlign=top;spacingLeft=12;spacingTop=8;",
  swim:
    "swimlane;startSize=32;rounded=1;arcSize=10;fillColor=#FFFFFF;swimlaneFillColor=#F8FAFC;strokeColor=#94A3B8;fontStyle=1;fontSize=13;fontColor=#334155;shadow=1;",
  edge:
    "edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#64748B;strokeWidth=1.5;fontSize=10;fontColor=#475569;fontFamily=Helvetica;labelBackgroundColor=#FFFFFF;labelBorderColor=#E2E8F0;exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;endArrow=blockThin;endFill=1;",
  edgeAccent:
    "edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#2563EB;strokeWidth=2;fontSize=10;fontColor=#1E40AF;fontFamily=Helvetica;labelBackgroundColor=#EFF6FF;labelBorderColor=#BFDBFE;endArrow=blockThin;endFill=1;",
};

function esc(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/** 首行加粗 + 次行灰色小字（属性内用单引号，避免破坏 XML） */
function richLabel(label) {
  const parts = String(label).split("\n");
  if (parts.length === 1) {
    return `&lt;font style=&apos;font-size:13px&apos;&gt;&lt;b&gt;${esc(parts[0])}&lt;/b&gt;&lt;/font&gt;`;
  }
  const head = `&lt;font style=&apos;font-size:13px&apos;&gt;&lt;b&gt;${esc(parts[0])}&lt;/b&gt;&lt;/font&gt;`;
  const rest = parts
    .slice(1)
    .map(
      (p) =>
        `&lt;font style=&apos;font-size:10px&apos; color=&apos;#64748B&apos;&gt;${esc(p)}&lt;/font&gt;`,
    )
    .join("&lt;br/&gt;");
  return `${head}&lt;br/&gt;${rest}`;
}

function buildMx({ name, title, nodes, edges, width = 960, height = 640 }) {
  const cellXml = [];
  cellXml.push(`<mxCell id="0"/>`);
  cellXml.push(`<mxCell id="1" parent="0"/>`);

  // 画布底衬 + 标题条
  const displayTitle = title || name.replace(/-/g, " · ");
  cellXml.push(
    `<mxCell id="_canvas" value="Tioka Architecture" style="${STYLE.canvas}" vertex="1" parent="1"><mxGeometry x="16" y="16" width="${width - 32}" height="${height - 32}" as="geometry"/></mxCell>`,
  );
  cellXml.push(
    `<mxCell id="_title" value="${esc(displayTitle)}" style="${STYLE.title}" vertex="1" parent="1"><mxGeometry x="36" y="36" width="${Math.min(420, width - 80)}" height="40" as="geometry"/></mxCell>`,
  );

  for (const n of nodes) {
    const style = STYLE[n.style || "box"] || STYLE.box;
    const y = (n.y || 0) + 70; // 为标题条留空
    const value = richLabel(n.label);
    cellXml.push(
      `<mxCell id="${esc(n.id)}" value="${value}" style="${style}" vertex="1" parent="${esc(n.parent || "1")}"><mxGeometry x="${n.x}" y="${y}" width="${n.w || 150}" height="${n.h || 64}" as="geometry"/></mxCell>`,
    );
  }
  let ei = 0;
  for (const e of edges) {
    const id = e.id || `e${++ei}`;
    const label = e.label ? ` value="${esc(e.label)}"` : "";
    const estyle = e.accent ? STYLE.edgeAccent : STYLE.edge;
    cellXml.push(
      `<mxCell id="${esc(id)}"${label} style="${estyle}" edge="1" parent="1" source="${esc(e.from)}" target="${esc(e.to)}"><mxGeometry relative="1" as="geometry"/></mxCell>`,
    );
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="app.diagrams.net" agent="TiokaGen" version="24.7.0">
  <diagram id="${esc(name)}" name="${esc(displayTitle)}">
    <mxGraphModel dx="1400" dy="900" grid="0" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="${width}" pageHeight="${height + 40}" background="#FFFFFF" math="0" shadow="1">
      <root>
        ${cellXml.join("\n        ")}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
`;
}

const TITLES = {
  "doc-hierarchy": "文档层级 · Document Map",
  "ba-capability-map": "业务能力地图 · Capability Map",
  "ba-poc-sequence": "PoC Gate 价值路径",
  "ta-c4-context": "C4 Context · 系统上下文",
  "ta-layers": "逻辑分层 · Layered Architecture",
  "ta-runtime-path": "运行时关键路径",
  "da-concept-er": "数据概念模型",
  "da-data-flow": "关键数据流",
  "aa-app-interaction": "应用交互总图",
  "ea-module-deps": "Java 模块依赖",
  "fe-ia-nav": "前端信息架构",
  "fe-part-renderer": "Part 渲染体系",
  "fe-dual-card": "双卡片可感知差异",
  "s0-lifecycle": "系统级生命周期",
  "s0-asset-mount": "资产挂载关系",
  "s1-turn-loop": "Runtime Turn Loop",
  "s2-orch-flow": "编排主链",
  "s3-chat-flow": "对话触发执行",
  "s4-knowledge-pipeline": "知识入库与引用",
  "s5-flow-publish": "Flow 发布与调用",
  "s6-connector": "连接器出网边界",
  "s7-hci-surfaces": "HCI 交互面",
  "s9-foundation": "Foundation 与 SPI",
  "s10-routing": "模型智能路由",
  "s11-agent-bindings": "Agent 发布挂载",
  "s12-mcp": "MCP Client / Server",
  "s13-skill-lifecycle": "Skill 生命周期",
  "s14-flow-mgmt": "Flow 管理控制面",
  "impl-waves": "W0–W5 交付波次",
  "mgmt-api-mq": "同步 API 与异步 MQ",
};

function write(name, spec) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, `${name}.drawio`);
  const height = (spec.height || 640) + 90;
  writeFileSync(
    path,
    buildMx({
      name,
      title: TITLES[name] || name,
      ...spec,
      height,
      width: spec.width || 960,
    }),
  );
  console.log("wrote", path);
}

const diagrams = {
  "doc-hierarchy": {
    width: 1000,
    height: 720,
    nodes: [
      { id: "root", label: "Tioka 文档", x: 400, y: 20, w: 160, style: "ctrl" },
      { id: "g1", label: "01-管理文档", x: 40, y: 120, w: 160, style: "box" },
      { id: "g2", label: "02-企业架构", x: 240, y: 120, w: 160, style: "box" },
      { id: "g3", label: "03-能力域详设", x: 440, y: 120, w: 180, style: "exec" },
      { id: "g4", label: "04-实施交付", x: 680, y: 120, w: 160, style: "ext" },
      { id: "s1", label: "总览与基座\nS0/S8/S9", x: 300, y: 260, w: 140, style: "exec" },
      { id: "s2", label: "执行与编排\nS1/S2", x: 460, y: 260, w: 140, style: "exec" },
      { id: "s3", label: "产品与交互\nS3/S4/S5/S7", x: 620, y: 260, w: 150, style: "exec" },
      { id: "s4", label: "连接器 S6", x: 400, y: 380, w: 120, style: "ext" },
      { id: "s5", label: "模型与资产\nS10–S14", x: 560, y: 380, w: 140, style: "ctrl" },
      { id: "e1", label: "BA TA DA\nAA EA FE", x: 240, y: 260, w: 140, style: "box" },
      { id: "i1", label: "30/31/32", x: 700, y: 260, w: 120, style: "ext" },
    ],
    edges: [
      { from: "root", to: "g1" },
      { from: "root", to: "g2" },
      { from: "root", to: "g3" },
      { from: "root", to: "g4" },
      { from: "g2", to: "e1" },
      { from: "g3", to: "s1" },
      { from: "g3", to: "s2" },
      { from: "g3", to: "s3" },
      { from: "g3", to: "s4" },
      { from: "g3", to: "s5" },
      { from: "g4", to: "i1" },
    ],
  },

  "ba-capability-map": {
    width: 1100,
    height: 700,
    nodes: [
      { id: "v", label: "Tioka 业务价值\n私有化 Agent 套件", x: 420, y: 20, w: 200, style: "human" },
      { id: "bc1", label: "BC1 身份组织", x: 40, y: 140, style: "box" },
      { id: "bc2", label: "BC2 对话助手", x: 200, y: 140, style: "exec" },
      { id: "bc3", label: "BC3 执行工具", x: 360, y: 140, style: "exec" },
      { id: "bc4", label: "BC4 模型成本", x: 520, y: 140, style: "ctrl" },
      { id: "bc5", label: "BC5 知识运营", x: 680, y: 140, style: "exec" },
      { id: "bc6", label: "BC6 流程运营", x: 840, y: 140, style: "exec" },
      { id: "bc7", label: "BC7 事件人审", x: 200, y: 280, style: "human" },
      { id: "bc8", label: "BC8 连接集成", x: 400, y: 280, style: "ext" },
      { id: "bc9", label: "BC9 治理合规", x: 600, y: 280, style: "box" },
      { id: "bc10", label: "BC10 平台工程", x: 800, y: 280, style: "box" },
    ],
    edges: [
      { from: "v", to: "bc1" },
      { from: "v", to: "bc2" },
      { from: "v", to: "bc3" },
      { from: "v", to: "bc4" },
      { from: "v", to: "bc5" },
      { from: "v", to: "bc6" },
      { from: "bc2", to: "bc7" },
      { from: "bc3", to: "bc8" },
      { from: "bc1", to: "bc9" },
      { from: "bc9", to: "bc10" },
    ],
  },

  "ba-poc-sequence": {
    width: 1000,
    height: 560,
    nodes: [
      { id: "it", label: "客户 IT", x: 40, y: 40, style: "human" },
      { id: "adm", label: "平台管理员", x: 220, y: 40, style: "human" },
      { id: "eng", label: "Agent 工程师", x: 420, y: 40, style: "human" },
      { id: "usr", label: "业务用户", x: 620, y: 40, style: "human" },
      { id: "tk", label: "Tioka", x: 400, y: 200, w: 180, h: 80, style: "exec" },
      { id: "gate", label: "PoC Gate\nW1 Exit", x: 400, y: 360, w: 180, style: "ext" },
    ],
    edges: [
      { from: "it", to: "tk", label: "SSO/中间件" },
      { from: "adm", to: "tk", label: "模型+MCP" },
      { from: "eng", to: "tk", label: "发布 Agent" },
      { from: "usr", to: "tk", label: "对话+工具" },
      { from: "tk", to: "gate", label: "演示达标", accent: true },
    ],
  },

  "ta-c4-context": {
    width: 1100,
    height: 700,
    nodes: [
      { id: "u", label: "用户/审批人/管理员", x: 420, y: 20, w: 180, style: "human" },
      { id: "tk", label: "Tioka 平台", x: 400, y: 200, w: 220, h: 90, style: "exec" },
      { id: "idp", label: "客户 IdP", x: 40, y: 200, style: "ext" },
      { id: "llm", label: "模型供应商/\n本地推理", x: 40, y: 360, style: "ext" },
      { id: "mcp", label: "MCP Server", x: 760, y: 200, style: "ext" },
      { id: "im", label: "飞书/钉钉/\n企微/自有 IM", x: 760, y: 360, style: "ext" },
      { id: "biz", label: "业务系统", x: 420, y: 420, style: "ext" },
      { id: "infra", label: "PG/Redis/MQ/OSS", x: 420, y: 540, w: 180, style: "data" },
    ],
    edges: [
      { from: "u", to: "tk" },
      { from: "idp", to: "tk", label: "OIDC/SAML" },
      { from: "tk", to: "llm", label: "HTTPS" },
      { from: "tk", to: "mcp", label: "HTTP/SSE" },
      { from: "tk", to: "im", label: "Adapter" },
      { from: "biz", to: "tk", label: "Webhook/API" },
      { from: "tk", to: "infra" },
    ],
  },

  "ta-layers": {
    width: 900,
    height: 560,
    nodes: [
      { id: "l1", label: "体验层 FE：Chat / Studio / 配置 / 审批", x: 120, y: 40, w: 640, h: 50, style: "human" },
      { id: "l2", label: "接入层：REST / SSE / Webhook / MCP HTTP", x: 120, y: 120, w: 640, h: 50, style: "box" },
      { id: "l3", label: "应用服务层：*-biz 用例编排", x: 120, y: 200, w: 640, h: 50, style: "exec" },
      { id: "l4", label: "领域层：聚合 / 领域服务 / 策略", x: 120, y: 280, w: 640, h: 50, style: "exec" },
      { id: "l5", label: "基础设施：DB / MQ / OSS / 向量 / SPI", x: 120, y: 360, w: 640, h: 50, style: "ext" },
    ],
    edges: [
      { from: "l1", to: "l2" },
      { from: "l2", to: "l3" },
      { from: "l3", to: "l4" },
      { from: "l4", to: "l5" },
    ],
  },

  "ta-runtime-path": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "ui", label: "UI SSE", x: 40, y: 160, style: "human" },
      { id: "chat", label: "Chat", x: 220, y: 160, style: "exec" },
      { id: "rt", label: "Runtime\nTurn Loop", x: 400, y: 140, w: 140, h: 80, style: "exec" },
      { id: "mr", label: "ModelRouter\nS10", x: 400, y: 40, style: "ctrl" },
      { id: "tr", label: "ToolRegistry", x: 600, y: 140, w: 140, h: 70, style: "box" },
      { id: "att", label: "Attention\nS2", x: 600, y: 280, style: "human" },
      { id: "tools", label: "MCP/KB/Flow/\nSkill/Builtin", x: 800, y: 140, w: 140, h: 80, style: "ext" },
    ],
    edges: [
      { from: "ui", to: "chat" },
      { from: "chat", to: "rt" },
      { from: "mr", to: "rt" },
      { from: "rt", to: "tr" },
      { from: "tr", to: "tools" },
      { from: "rt", to: "att", label: "suspend" },
    ],
  },

  "da-concept-er": {
    width: 1100,
    height: 720,
    nodes: [
      { id: "ws", label: "Workspace", x: 460, y: 40, style: "box" },
      { id: "pr", label: "Principal", x: 40, y: 40, style: "human" },
      { id: "ag", label: "Agent\nVersion", x: 200, y: 200, style: "ctrl" },
      { id: "rp", label: "RoutingPlan", x: 40, y: 200, style: "ctrl" },
      { id: "mcp", label: "McpServer\nGrant", x: 360, y: 200, style: "ext" },
      { id: "sk", label: "Skill", x: 520, y: 200, style: "ctrl" },
      { id: "fl", label: "Flow\nPublication", x: 680, y: 200, style: "exec" },
      { id: "cv", label: "Conversation\nMessage/Part", x: 200, y: 380, style: "exec" },
      { id: "ss", label: "Runtime\nSession/Turn", x: 400, y: 380, style: "exec" },
      { id: "or", label: "Orch Run\nAttention", x: 600, y: 380, style: "human" },
      { id: "kb", label: "KB/Doc/\nChunk", x: 800, y: 380, style: "exec" },
    ],
    edges: [
      { from: "pr", to: "ws" },
      { from: "ws", to: "ag" },
      { from: "ag", to: "rp" },
      { from: "ag", to: "mcp" },
      { from: "ag", to: "sk" },
      { from: "ag", to: "fl" },
      { from: "ag", to: "cv" },
      { from: "cv", to: "ss" },
      { from: "ss", to: "or" },
      { from: "ss", to: "kb", label: "tool" },
      { from: "fl", to: "ss", label: "tool" },
    ],
  },

  "da-data-flow": {
    width: 1000,
    height: 480,
    nodes: [
      { id: "user", label: "User Message", x: 40, y: 200, style: "human" },
      { id: "ch", label: "ch_* 落库", x: 220, y: 200, style: "exec" },
      { id: "rt", label: "Runtime Turn", x: 400, y: 200, style: "exec" },
      { id: "part", label: "Part 追加", x: 580, y: 200, style: "box" },
      { id: "usage", label: "usage_ledger", x: 760, y: 200, style: "data" },
      { id: "pub", label: "配置发布\n不可变快照", x: 400, y: 40, style: "ctrl" },
      { id: "mq", label: "MQ *.published", x: 580, y: 40, style: "ext" },
      { id: "cache", label: "执行侧缓存失效", x: 760, y: 40, style: "box" },
    ],
    edges: [
      { from: "user", to: "ch" },
      { from: "ch", to: "rt" },
      { from: "rt", to: "part" },
      { from: "part", to: "usage" },
      { from: "pub", to: "mq" },
      { from: "mq", to: "cache" },
    ],
  },

  "aa-app-interaction": {
    width: 1100,
    height: 640,
    nodes: [
      { id: "ui", label: "tioka-ui", x: 460, y: 20, w: 160, style: "human" },
      { id: "chat", label: "chat-api", x: 200, y: 160, style: "exec" },
      { id: "agent", label: "agent/model/mcp", x: 420, y: 160, w: 160, style: "ctrl" },
      { id: "orch", label: "orch-api", x: 660, y: 160, style: "human" },
      { id: "rt", label: "runtime-api", x: 420, y: 300, w: 160, h: 70, style: "exec" },
      { id: "flow", label: "flow-api", x: 660, y: 300, style: "exec" },
      { id: "kn", label: "knowledge-api", x: 200, y: 440, style: "exec" },
      { id: "co", label: "connector\n出网唯一", x: 420, y: 440, w: 160, style: "ext" },
      { id: "md", label: "model-api", x: 660, y: 440, style: "ctrl" },
    ],
    edges: [
      { from: "ui", to: "chat" },
      { from: "ui", to: "agent" },
      { from: "ui", to: "orch" },
      { from: "chat", to: "rt" },
      { from: "agent", to: "rt" },
      { from: "orch", to: "rt" },
      { from: "flow", to: "rt" },
      { from: "rt", to: "md" },
      { from: "rt", to: "co" },
      { from: "kn", to: "co" },
      { from: "rt", to: "kn", label: "tool" },
    ],
  },

  "ea-module-deps": {
    width: 1100,
    height: 700,
    nodes: [
      { id: "fw", label: "framework", x: 460, y: 20, style: "box" },
      { id: "sys", label: "system-api", x: 460, y: 120, style: "box" },
      { id: "md", label: "model-api", x: 200, y: 240, style: "ctrl" },
      { id: "rt", label: "runtime-api", x: 400, y: 240, style: "exec" },
      { id: "ag", label: "agent-api", x: 600, y: 240, style: "ctrl" },
      { id: "mcp", label: "mcp-api", x: 800, y: 240, style: "ext" },
      { id: "ch", label: "chat-api", x: 300, y: 400, style: "exec" },
      { id: "or", label: "orch-api", x: 500, y: 400, style: "human" },
      { id: "fl", label: "flow-api", x: 700, y: 400, style: "exec" },
      { id: "kn", label: "knowledge-api", x: 200, y: 540, style: "exec" },
      { id: "co", label: "connector-api", x: 500, y: 540, style: "ext" },
    ],
    edges: [
      { from: "sys", to: "fw" },
      { from: "md", to: "sys" },
      { from: "rt", to: "sys" },
      { from: "rt", to: "md" },
      { from: "ag", to: "sys" },
      { from: "ag", to: "md" },
      { from: "ag", to: "mcp" },
      { from: "ch", to: "rt" },
      { from: "ch", to: "ag" },
      { from: "or", to: "rt" },
      { from: "fl", to: "rt" },
      { from: "kn", to: "co" },
      { from: "co", to: "or" },
    ],
  },

  "fe-ia-nav": {
    width: 1000,
    height: 560,
    nodes: [
      { id: "shell", label: "tioka-ui 壳", x: 400, y: 20, w: 160, style: "human" },
      { id: "chat", label: "对话 W1", x: 40, y: 160, style: "exec" },
      { id: "appr", label: "审批 W2", x: 200, y: 160, style: "human" },
      { id: "obs", label: "观测 W2", x: 360, y: 160, style: "box" },
      { id: "kb", label: "知识 W3", x: 520, y: 160, style: "exec" },
      { id: "st", label: "Studio W4", x: 680, y: 160, style: "exec" },
      { id: "cfg", label: "配置台", x: 400, y: 300, w: 160, style: "ctrl" },
      { id: "a", label: "Agent", x: 160, y: 420, style: "ctrl" },
      { id: "m", label: "模型", x: 320, y: 420, style: "ctrl" },
      { id: "p", label: "MCP", x: 480, y: 420, style: "ext" },
      { id: "sk", label: "Skill", x: 640, y: 420, style: "ctrl" },
    ],
    edges: [
      { from: "shell", to: "chat" },
      { from: "shell", to: "appr" },
      { from: "shell", to: "obs" },
      { from: "shell", to: "kb" },
      { from: "shell", to: "st" },
      { from: "shell", to: "cfg" },
      { from: "cfg", to: "a" },
      { from: "cfg", to: "m" },
      { from: "cfg", to: "p" },
      { from: "cfg", to: "sk" },
    ],
  },

  "fe-part-renderer": {
    width: 900,
    height: 520,
    nodes: [
      { id: "sse", label: "SSE/API\nPart JSON", x: 40, y: 200, style: "box" },
      { id: "pr", label: "PartRenderer", x: 240, y: 180, w: 140, h: 80, style: "human" },
      { id: "t", label: "Text/\nMarkdown", x: 460, y: 40, style: "exec" },
      { id: "tool", label: "ToolCall/\nResult", x: 460, y: 140, style: "exec" },
      { id: "card", label: "Interactive/\nApproval Card", x: 460, y: 240, style: "human" },
      { id: "cit", label: "Citation/\nArtifact", x: 460, y: 340, style: "exec" },
      { id: "fb", label: "Fallback", x: 660, y: 200, style: "ext" },
    ],
    edges: [
      { from: "sse", to: "pr" },
      { from: "pr", to: "t" },
      { from: "pr", to: "tool" },
      { from: "pr", to: "card" },
      { from: "pr", to: "cit" },
      { from: "pr", to: "fb", label: "未知 type" },
    ],
  },

  "s0-lifecycle": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "stim", label: "刺激\n用户/事件/IM", x: 40, y: 160, style: "human" },
      { id: "entry", label: "Chat|Orch|Flow", x: 220, y: 160, style: "box" },
      { id: "rt", label: "Runtime Session", x: 400, y: 160, style: "exec" },
      { id: "tools", label: "Tools\nKB/Flow/MCP", x: 580, y: 80, style: "ext" },
      { id: "hci", label: "Part/Attention", x: 580, y: 240, style: "human" },
      { id: "out", label: "写回 Chat/IM/\n业务/知识", x: 780, y: 160, style: "exec" },
    ],
    edges: [
      { from: "stim", to: "entry" },
      { from: "entry", to: "rt" },
      { from: "rt", to: "tools" },
      { from: "rt", to: "hci" },
      { from: "tools", to: "out" },
      { from: "hci", to: "out" },
    ],
  },

  "s0-asset-mount": {
    width: 900,
    height: 480,
    nodes: [
      { id: "ag", label: "Agent S11", x: 360, y: 40, w: 160, h: 70, style: "ctrl" },
      { id: "rp", label: "RoutingPlan S10", x: 40, y: 200, style: "ctrl" },
      { id: "mcp", label: "McpGrant S12", x: 260, y: 200, style: "ext" },
      { id: "sk", label: "SkillBinding S13", x: 480, y: 200, style: "ctrl" },
      { id: "fl", label: "FlowPublication S14", x: 700, y: 200, w: 160, style: "exec" },
      { id: "rt", label: "Runtime S1\n只读已发布快照", x: 340, y: 360, w: 200, h: 70, style: "exec" },
    ],
    edges: [
      { from: "ag", to: "rp" },
      { from: "ag", to: "mcp" },
      { from: "ag", to: "sk" },
      { from: "ag", to: "fl" },
      { from: "rp", to: "rt" },
      { from: "mcp", to: "rt" },
      { from: "sk", to: "rt" },
      { from: "fl", to: "rt" },
    ],
  },

  "s1-turn-loop": {
    width: 1000,
    height: 480,
    nodes: [
      { id: "in", label: "append/steer", x: 40, y: 200, style: "box" },
      { id: "plan", label: "选模\nModelRouter", x: 200, y: 200, style: "ctrl" },
      { id: "llm", label: "模型调用\n流式 Part", x: 380, y: 200, style: "exec" },
      { id: "tool", label: "Tool 调用?", x: 560, y: 200, w: 140, h: 100, style: "decision" },
      { id: "att", label: "Attention\n挂起?", x: 560, y: 360, style: "human" },
      { id: "done", label: "Turn 完成", x: 760, y: 200, style: "exec" },
    ],
    edges: [
      { from: "in", to: "plan" },
      { from: "plan", to: "llm" },
      { from: "llm", to: "tool" },
      { from: "tool", to: "llm", label: "结果回灌" },
      { from: "tool", to: "att", label: "suspend" },
      { from: "tool", to: "done", label: "无工具" },
      { from: "att", to: "llm", label: "续跑" },
    ],
  },

  "s2-orch-flow": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "ev", label: "Event 入站", x: 40, y: 180, style: "ext" },
      { id: "rule", label: "Rule 匹配", x: 200, y: 180, style: "box" },
      { id: "wi", label: "WorkItem", x: 360, y: 180, style: "exec" },
      { id: "run", label: "Run 租约", x: 520, y: 180, style: "exec" },
      { id: "att", label: "Attention", x: 680, y: 80, style: "human" },
      { id: "why", label: "why 时间线", x: 680, y: 280, style: "box" },
      { id: "done", label: "完成/回执", x: 840, y: 180, style: "exec" },
    ],
    edges: [
      { from: "ev", to: "rule" },
      { from: "rule", to: "wi" },
      { from: "wi", to: "run" },
      { from: "run", to: "att" },
      { from: "run", to: "why" },
      { from: "att", to: "run", label: "应答" },
      { from: "run", to: "done" },
    ],
  },

  "s3-chat-flow": {
    width: 960,
    height: 400,
    nodes: [
      { id: "u", label: "用户消息", x: 40, y: 160, style: "human" },
      { id: "cv", label: "Conversation", x: 200, y: 160, style: "exec" },
      { id: "ag", label: "AgentVersion", x: 360, y: 160, style: "ctrl" },
      { id: "rt", label: "Runtime", x: 520, y: 160, style: "exec" },
      { id: "sse", label: "SSE Parts", x: 680, y: 160, style: "human" },
      { id: "store", label: "Part 落库", x: 840, y: 160, style: "box" },
    ],
    edges: [
      { from: "u", to: "cv" },
      { from: "cv", to: "ag" },
      { from: "ag", to: "rt" },
      { from: "rt", to: "sse" },
      { from: "sse", to: "store" },
    ],
  },

  "s4-knowledge-pipeline": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "src", label: "上传/同步源", x: 40, y: 180, style: "ext" },
      { id: "oss", label: "OSS 原文", x: 200, y: 180, style: "box" },
      { id: "parse", label: "解析分块", x: 360, y: 180, style: "exec" },
      { id: "vec", label: "Embedding\n向量库", x: 520, y: 180, style: "data" },
      { id: "ret", label: "混合检索", x: 680, y: 180, style: "exec" },
      { id: "cit", label: "Citation", x: 840, y: 180, style: "human" },
    ],
    edges: [
      { from: "src", to: "oss" },
      { from: "oss", to: "parse" },
      { from: "parse", to: "vec" },
      { from: "vec", to: "ret" },
      { from: "ret", to: "cit" },
    ],
  },

  "s5-flow-publish": {
    width: 1000,
    height: 480,
    nodes: [
      { id: "draft", label: "Flow Draft", x: 40, y: 200, style: "box" },
      { id: "ver", label: "FlowVersion\n发布", x: 220, y: 200, style: "exec" },
      { id: "api", label: "API 通道", x: 440, y: 80, style: "ext" },
      { id: "tool", label: "Flow-as-Tool", x: 440, y: 200, style: "ctrl" },
      { id: "mcp", label: "MCP 出口", x: 440, y: 320, style: "ext" },
      { id: "ag", label: "Agent 挂载", x: 680, y: 200, style: "ctrl" },
      { id: "run", label: "FlowRun", x: 860, y: 200, style: "exec" },
    ],
    edges: [
      { from: "draft", to: "ver" },
      { from: "ver", to: "api" },
      { from: "ver", to: "tool" },
      { from: "ver", to: "mcp" },
      { from: "tool", to: "ag" },
      { from: "ag", to: "run" },
      { from: "api", to: "run" },
    ],
  },

  "s6-connector": {
    width: 960,
    height: 480,
    nodes: [
      { id: "spi", label: "Adapter SPI\nS9", x: 400, y: 20, w: 160, style: "box" },
      { id: "im", label: "IM Adapter\n飞书/钉钉/企微", x: 40, y: 180, w: 160, style: "ext" },
      { id: "biz", label: "Biz Adapter", x: 240, y: 180, style: "ext" },
      { id: "doc", label: "Doc Sync", x: 440, y: 180, style: "exec" },
      { id: "mcp", label: "MCP 宿主", x: 640, y: 180, style: "ctrl" },
      { id: "ds", label: "数据源同步", x: 840, y: 180, style: "exec" },
      { id: "out", label: "出网唯一边界", x: 360, y: 360, w: 200, style: "human" },
    ],
    edges: [
      { from: "spi", to: "im" },
      { from: "spi", to: "biz" },
      { from: "im", to: "out" },
      { from: "biz", to: "out" },
      { from: "doc", to: "out" },
      { from: "mcp", to: "out" },
      { from: "ds", to: "out" },
    ],
  },

  "s7-hci-surfaces": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "p", label: "Part 规范\n唯一渲染", x: 400, y: 20, w: 160, style: "ctrl" },
      { id: "chat", label: "Chat 工作台", x: 40, y: 180, style: "exec" },
      { id: "appr", label: "审批台", x: 200, y: 180, style: "human" },
      { id: "kb", label: "知识台", x: 360, y: 180, style: "exec" },
      { id: "st", label: "Flow Studio", x: 520, y: 180, style: "exec" },
      { id: "cfg", label: "配置台", x: 680, y: 180, style: "ctrl" },
      { id: "obs", label: "观测台", x: 840, y: 180, style: "box" },
    ],
    edges: [
      { from: "p", to: "chat" },
      { from: "p", to: "appr" },
      { from: "p", to: "kb" },
      { from: "p", to: "st" },
      { from: "p", to: "cfg" },
      { from: "p", to: "obs" },
    ],
  },

  "s9-foundation": {
    width: 1000,
    height: 520,
    nodes: [
      { id: "core", label: "Foundation\n鉴权/RBAC/空间", x: 380, y: 40, w: 180, h: 70, style: "box" },
      { id: "idp", label: "IdP 联邦", x: 40, y: 200, style: "ext" },
      { id: "org", label: "组织同步\nSCIM", x: 200, y: 200, style: "ext" },
      { id: "sec", label: "SecretStore", x: 380, y: 200, style: "ctrl" },
      { id: "aud", label: "AuditSink", x: 560, y: 200, style: "box" },
      { id: "adp", label: "Adapter\nRegistry", x: 740, y: 200, style: "exec" },
      { id: "dom", label: "各业务域", x: 400, y: 380, w: 160, style: "exec" },
    ],
    edges: [
      { from: "idp", to: "core" },
      { from: "org", to: "core" },
      { from: "core", to: "sec" },
      { from: "core", to: "aud" },
      { from: "core", to: "adp" },
      { from: "core", to: "dom", label: "PermissionEvaluator" },
    ],
  },

  "s10-routing": {
    width: 1000,
    height: 480,
    nodes: [
      { id: "prov", label: "Provider", x: 40, y: 80, style: "ext" },
      { id: "lm", label: "LogicalModel", x: 220, y: 80, style: "ctrl" },
      { id: "bind", label: "Binding×N", x: 400, y: 80, style: "ctrl" },
      { id: "plan", label: "RoutingPlan\n发布快照", x: 400, y: 240, w: 160, h: 70, style: "exec" },
      { id: "dec", label: "决策/启发式", x: 200, y: 240, style: "box" },
      { id: "rt", label: "Runtime\n同轮冻结", x: 640, y: 240, style: "exec" },
      { id: "fb", label: "故障接力", x: 820, y: 240, style: "ext" },
    ],
    edges: [
      { from: "prov", to: "lm" },
      { from: "lm", to: "bind" },
      { from: "bind", to: "plan" },
      { from: "dec", to: "plan" },
      { from: "plan", to: "rt" },
      { from: "rt", to: "fb" },
    ],
  },

  "s11-agent-bindings": {
    width: 900,
    height: 420,
    nodes: [
      { id: "d", label: "AgentDraft", x: 40, y: 180, style: "box" },
      { id: "v", label: "AgentVersion\n快照哈希", x: 240, y: 160, w: 150, h: 80, style: "ctrl" },
      { id: "r", label: "Route", x: 480, y: 40, style: "ctrl" },
      { id: "m", label: "MCP", x: 480, y: 140, style: "ext" },
      { id: "s", label: "Skill", x: 480, y: 240, style: "ctrl" },
      { id: "f", label: "Flow Tool", x: 480, y: 340, style: "exec" },
      { id: "rt", label: "Runtime 装载", x: 700, y: 180, style: "exec" },
    ],
    edges: [
      { from: "d", to: "v" },
      { from: "v", to: "r" },
      { from: "v", to: "m" },
      { from: "v", to: "s" },
      { from: "v", to: "f" },
      { from: "v", to: "rt" },
    ],
  },

  "s12-mcp": {
    width: 1000,
    height: 480,
    nodes: [
      { id: "ext", label: "外部 MCP\nHTTP/SSE", x: 40, y: 120, style: "ext" },
      { id: "reg", label: "McpServer\n登记", x: 240, y: 120, style: "ctrl" },
      { id: "disc", label: "tools/list\n缓存", x: 440, y: 120, style: "box" },
      { id: "grant", label: "Grant+\nallowlist", x: 640, y: 120, style: "ctrl" },
      { id: "rt", label: "Runtime\nToolProvider", x: 840, y: 120, style: "exec" },
      { id: "out", label: "Tioka MCP\nOutbound", x: 440, y: 320, w: 160, style: "exec" },
      { id: "cli", label: "外部 Client", x: 680, y: 320, style: "ext" },
    ],
    edges: [
      { from: "ext", to: "reg" },
      { from: "reg", to: "disc" },
      { from: "disc", to: "grant" },
      { from: "grant", to: "rt" },
      { from: "out", to: "cli" },
    ],
  },

  "s13-skill-lifecycle": {
    width: 1000,
    height: 360,
    nodes: [
      { id: "up", label: "ZIP/Git\n导入", x: 40, y: 140, style: "ext" },
      { id: "scan", label: "扫描校验", x: 200, y: 140, style: "box" },
      { id: "ready", label: "READY", x: 360, y: 140, style: "exec" },
      { id: "pub", label: "PUBLISHED", x: 520, y: 140, style: "ctrl" },
      { id: "bind", label: "Agent 绑定\nmode", x: 700, y: 140, style: "ctrl" },
      { id: "rt", label: "Runtime 注入", x: 860, y: 140, style: "exec" },
    ],
    edges: [
      { from: "up", to: "scan" },
      { from: "scan", to: "ready" },
      { from: "ready", to: "pub" },
      { from: "pub", to: "bind" },
      { from: "bind", to: "rt" },
    ],
  },

  "s14-flow-mgmt": {
    width: 1000,
    height: 420,
    nodes: [
      { id: "proj", label: "FlowProject", x: 40, y: 180, style: "box" },
      { id: "draft", label: "Draft+校验", x: 200, y: 180, style: "box" },
      { id: "ver", label: "Version", x: 360, y: 180, style: "exec" },
      { id: "pub", label: "Publication\n多通道", x: 520, y: 180, style: "ctrl" },
      { id: "ops", label: "Ops 运维", x: 700, y: 180, style: "human" },
      { id: "eng", label: "S5 引擎", x: 860, y: 180, style: "exec" },
    ],
    edges: [
      { from: "proj", to: "draft" },
      { from: "draft", to: "ver" },
      { from: "ver", to: "pub" },
      { from: "pub", to: "ops" },
      { from: "pub", to: "eng" },
    ],
  },

  "impl-waves": {
    width: 1100,
    height: 400,
    nodes: [
      { id: "w0", label: "W0\n契约骨架", x: 40, y: 160, style: "box" },
      { id: "w1", label: "W1\nPoC Gate", x: 200, y: 160, style: "ext" },
      { id: "w2", label: "W2\nOrch+IM", x: 360, y: 160, style: "human" },
      { id: "w3", label: "W3\nKnowledge", x: 520, y: 80, style: "exec" },
      { id: "w4", label: "W4\nFlow", x: 520, y: 240, style: "exec" },
      { id: "w5", label: "W5\n密度+HA", x: 720, y: 160, style: "ctrl" },
      { id: "g", label: "G1–G7\n全门禁", x: 900, y: 160, style: "human" },
    ],
    edges: [
      { from: "w0", to: "w1" },
      { from: "w1", to: "w2" },
      { from: "w2", to: "w3" },
      { from: "w2", to: "w4" },
      { from: "w3", to: "w5" },
      { from: "w4", to: "w5" },
      { from: "w5", to: "g" },
    ],
  },

  "fe-dual-card": {
    width: 900,
    height: 400,
    nodes: [
      { id: "cont", label: "interactive_card\nmode=continue\n对话内继续", x: 120, y: 140, w: 200, h: 90, style: "exec" },
      { id: "susp", label: "approval_card\nmode=suspend\n挂起 Attention", x: 520, y: 140, w: 220, h: 90, style: "human" },
      { id: "tok1", label: "--card-continue-accent", x: 120, y: 280, w: 200, style: "exec" },
      { id: "tok2", label: "--card-suspend-accent", x: 520, y: 280, w: 220, style: "human" },
      { id: "title", label: "双卡片可感知差异（ADR-004）", x: 280, y: 40, w: 280, style: "box" },
    ],
    edges: [
      { from: "title", to: "cont" },
      { from: "title", to: "susp" },
      { from: "cont", to: "tok1" },
      { from: "susp", to: "tok2" },
    ],
  },

  "mgmt-api-mq": {
    width: 960,
    height: 420,
    nodes: [
      { id: "api", label: "同步 -api\nREST/RPC/SSE", x: 200, y: 80, w: 180, style: "box" },
      { id: "mq", label: "异步 MQ\ntioka-*", x: 520, y: 80, w: 180, style: "ext" },
      { id: "chat", label: "Chat/Runtime", x: 80, y: 260, style: "exec" },
      { id: "orch", label: "Orch/IM", x: 280, y: 260, style: "human" },
      { id: "asset", label: "资产发布", x: 480, y: 260, style: "ctrl" },
      { id: "kb", label: "知识同步", x: 680, y: 260, style: "exec" },
    ],
    edges: [
      { from: "chat", to: "api" },
      { from: "orch", to: "mq" },
      { from: "asset", to: "mq" },
      { from: "kb", to: "mq" },
      { from: "api", to: "mq", label: "生命周期" },
    ],
  },
};

for (const [name, spec] of Object.entries(diagrams)) {
  write(name, spec);
}

console.log(`\nGenerated ${Object.keys(diagrams).length} diagrams → ${OUT}`);

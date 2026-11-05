# S5 — Flow 工作流（完整能力）

> 对标：Langflow 可视化 Agent/RAG 编排平台能力并集  
> **分工**：本文 = **执行引擎与节点能力**；项目管理 / 版本发布 / 通道 / 运维控制面见 **S14**

---

## 1. 定位与边界

可视化构建、调试、发布 Agent 与确定性工作流。Agent 节点内部必须调用 S1；人卡走 S2/S3；知识走 S4。管理台生命周期与 Publication 登记走 S14。

---

## 2. 完整能力地图

| 能力簇 | 具体能力 | 对标 Langflow | 波次 |
| --- | --- | --- | --- |
| **画布** | 拖拽、对齐、分组、注释、小地图、只读预览 | editor | W4 |
| **图语义** | DAG；条件分支；并行网关；循环（有界）；子流程 | flows | W4–W5 |
| **节点库 - IO** | Chat/API/Webhook Input、Output、文件 IO | IO | W4 |
| **节点库 - 模型** | 多厂商 LLM、Embedding、Router | LLM | W4 |
| **节点库 - Agent** | Agent、Tool Mode、Subflow-as-Tool | Agents | W4 |
| **节点库 - 知识** | Retrieve、Index、Rerank、FAQ | RAG | W4 |
| **节点库 - 工具** | MCP、HTTP、业务连接器、代码节点 | tools | W4–W5 |
| **节点库 - 控制** | Branch、Loop、Delay、HumanCard、Approval | control | W4 |
| **节点库 - 数据** | Prompt、Parser、JSON/SQL transform | data | W4 |
| **自定义节点** | SDK 开发、热插拔、签名发布 | custom python | W5 |
| **Tool Mode** | 任意节点/流暴露为 Agent 工具 | Tool Mode | W4 |
| **Playground** | 逐步运行、看 IO、改记忆、单节点测 | Playground | W4 |
| **发布** | REST API、SDK 片段、MCP Server、Embed、定时触发、Orch 规则触发 | Share/API/MCP | W4–W5 |
| **版本协作** | 版本、diff、回滚、评论、权限 | export/version | W4–W5 |
| **模板市场** | 官方/租户模板、导入导出 JSON | templates | W5 |
| **观测** | 节点耗时、token、对接 Trace | observability | W4 |
| **多 Agent 流** | 编排多个 Agent 节点协作 | multi-agent | W5 |

---

## 3–14. 结构摘要

实体：FlowDefinition、Node、Edge、FlowRun、NodeRun、Publication、NodePackage。  
引擎：DAG/状态机；Human/Approval 等待；Agent 调 Runtime。  
波次：W4 主链；W5 自定义节点/市场/循环并行/多 Agent。

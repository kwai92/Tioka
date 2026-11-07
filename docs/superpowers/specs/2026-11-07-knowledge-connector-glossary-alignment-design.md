# Design: Knowledge / Connector 名词对齐（闭环 #7）

- 日期：2026-11-07
- 状态：Accepted（已完成；V140/IngestJob 登记遗留）
- 前置：orch/flow；策略 B；最终域

## 决策

| 项 | 定案 |
| --- | --- |
| 表前缀 | Knowledge 冻结 `kb_*`；IM 目标 `im_*` |
| WikiPage | 产品权威；类名 `WikiPageDO`；表暂 `kb_wiki` |
| Citation | GL 标明「检索写表」为过渡；本闭环不改检索行为 |
| ImBinding / SyncJob | GL 权威；无 DO 时登记未落地；≠ IngestJob |
| DocSyncMapping | 产品权威；现行 `FeishuDocMapDO`/`feishu_doc_map` |
| MCP | 只认 §1.3；S0 Connector 行去掉 McpServer |
| 排除 | TenantContext；V140/V142 合入；检索停写 citation |

## 验收

- 47-GL § Knowledge / § Connector 就位
- WikiPageDO 类名；S0 无裸 Binding/McpServer（Connector 行）
- KnowledgeAclIT / KnowledgeEmbeddingIT 绿

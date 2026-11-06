# ADR-018 — Knowledge 入库、检索与数据源

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：KNW-01〜08
- 对应设计：设计 D
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 KNW-01〜08。核心决定是：**入库改为异步任务 + 版本切换，检索改为「权限过滤 → 混合召回 → Rerank → 父块扩展」四段，所有模型调用走 ModelGateway**。现有问题对应：默认 HashEmbedding、固定 200 字切块、同步 ingest 无事务、每次检索写 citation、无权限过滤。

**入库流水线**（KNW-03、KNW-05）

1. **登记**：上传或数据源变更只写 `kb_document` 和一条 `kb_ingest_job`，立即返回。按 `content_hash` 去重，内容未变则跳过。
2. **解析**：`DocumentParser` SPI 按格式选择实现（现有 PDF、DOCX，补 Markdown、HTML；OCR、图片、Excel 留给 M4），输出带标题层级的结构化段落。
3. **切块**：按标题切出父块（一个小节，上限 2000 字），父块内再切子块（约 400 token，重叠 50）；表格和代码块整块保留。策略按知识库可配。
4. **向量化**：批量调 `ModelGateway.embed`（purpose=embedding），用量计入知识库所属空间。
5. **索引与切换**：新版本分块写入时带 `doc_version`，全部成功后在一个事务内把 `kb_document.active_version` 切到新版本并删除旧版本，检索永远看不到半条数据。

各阶段由任务表驱动，失败可按阶段重试（最多 3 次）。M2 之前用数据库轮询派发任务，M2 后改由 RocketMQ 触发，任务表不变。

**检索链路**（KNW-02、KNW-04、KNW-08）

| 步骤 | 做法 |
| --- | --- |
| 权限过滤 | 用 `ResourceGuard.visibleIds("kb", USE)` 得到可见知识库，作为 SQL 条件下推，而不是召回后再过滤 |
| 混合召回 | 向量（pgvector HNSW）与关键词（PostgreSQL 全文索引）各取 50 条子块，RRF 融合（k=60，沿用现有实现） |
| Rerank | 前 50 条调 `ModelGateway.rerank` 取 topK；知识库未绑定 rerank 模型时跳过 |
| 父块扩展 | 返回子块用于引用定位，父块正文用于给模型上下文，同一父块只返回一次 |
| 引用 | 检索不再写 `kb_citation`；引用以 `chunkId@docVersion` 作为稳定引用号，由 Runtime 在回答实际采用时写成 citation item |

中文关键词检索需要 PostgreSQL 分词扩展（如 zhparser）。私有化环境装不了扩展时，退化为应用层分词后写入 `tsvector`，索引结构不变。

```java
public interface KnowledgeSearchApi {                     // 替代现有 searchKnowledge(kbId, query, topK)
    SearchResult search(SearchRequest req);               // Principal 取自 PrincipalHolder
}
record SearchRequest(List<String> kbIds, String query, int topK,
                     Map<String, String> metadataFilter, boolean rerank) {}
record Hit(String docId, String docVersion, String chunkId, String title, List<String> headingPath,
           String chunkText, String parentText, double score, String citationRef) {}
```

**数据源连接器**（KNW-06）

```java
public interface DataSourceConnector {
    String type();                                         // feishu_wiki, feishu_drive, confluence ...
    ConnectorSpec spec();                                  // 配置项 JSON Schema，供前端生成表单
    SyncCursor listChanges(DataSourceConfig cfg, SyncCursor since, Consumer<ChangeEvent> sink);
    FetchedDocument fetch(DataSourceConfig cfg, String externalId);
}
record ChangeEvent(String externalId, ChangeKind kind,     // UPSERT / DELETE
                   String externalVersion, String title, Instant updatedAt) {}
```

飞书用事件订阅拿增量（依赖 M2 的 CON-02 入站），再加每日一次全量对账兆底；其余数据源按计划轮询 `listChanges`。凭证只存 SecretStore 引用；代用户授权的飞书文档走 FND-08 令牌保管。另外两类数据源建议 Confluence 和 GitLab，待你确认。

**评测进 CI**（KNW-07）：评测集由「问题 + 期望命中的文档或分块」组成，指标取 Recall@5、MRR。CI 中用容器跑一个小型本地中文 embedding 模型（如 bge-small-zh），保证结果可复现；Recall@5 比基线低 2 个百分点以上则阻塞合并。

**数据模型**

```sql
ALTER TABLE kb_base ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE kb_base ADD COLUMN embedding_binding_id VARCHAR(64);  -- 绑定 Model Hub 的嵌入模型
ALTER TABLE kb_base ADD COLUMN rerank_binding_id VARCHAR(64);
ALTER TABLE kb_base ADD COLUMN chunk_policy_json TEXT;

ALTER TABLE kb_document ADD COLUMN datasource_id VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN external_id VARCHAR(255);
ALTER TABLE kb_document ADD COLUMN external_version VARCHAR(128);
ALTER TABLE kb_document ADD COLUMN content_hash VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN active_version INT NOT NULL DEFAULT 0;

CREATE TABLE kb_ingest_job (
  id VARCHAR(32) PRIMARY KEY, doc_id VARCHAR(64) NOT NULL, doc_version INT NOT NULL,
  stage VARCHAR(16) NOT NULL,            -- PARSE / CHUNK / EMBED / SWAP
  status VARCHAR(16) NOT NULL,           -- QUEUED / RUNNING / DONE / FAILED
  attempts INT NOT NULL DEFAULT 0, run_as_json TEXT NOT NULL, error TEXT,
  lease_until TIMESTAMP, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL);

-- kb_chunk 重建：embedding 从 CLOB 改为 vector，增加父子层级与全文索引
CREATE TABLE kb_chunk (
  id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(64) NOT NULL, doc_id VARCHAR(64) NOT NULL,
  doc_version INT NOT NULL, parent_id VARCHAR(32), level VARCHAR(8) NOT NULL,   -- PARENT / CHILD
  ordinal INT NOT NULL, heading_path TEXT, text TEXT NOT NULL, token_count INT,
  tsv TSVECTOR, embedding VECTOR(1024));
CREATE INDEX idx_chunk_vec ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_tsv ON kb_chunk USING gin (tsv);

ALTER TABLE kb_datasource ADD COLUMN config_json TEXT;      -- 含 secretRef，不含明文凭证
ALTER TABLE kb_datasource ADD COLUMN cursor_json TEXT;
ALTER TABLE kb_datasource ADD COLUMN schedule VARCHAR(64);
ALTER TABLE kb_datasource ADD COLUMN last_sync_at TIMESTAMP;
ALTER TABLE kb_datasource ADD COLUMN last_error TEXT;

CREATE TABLE kb_eval_case (id VARCHAR(32) PRIMARY KEY, set_id VARCHAR(32) NOT NULL,
  query TEXT NOT NULL, expected_refs TEXT NOT NULL);
CREATE TABLE kb_eval_run (id VARCHAR(32) PRIMARY KEY, set_id VARCHAR(32) NOT NULL,
  kb_id VARCHAR(64), config_json TEXT, recall_at_5 DOUBLE PRECISION, mrr DOUBLE PRECISION,
  created_at TIMESTAMP NOT NULL);
```

向量维度按部署统一（示例 1024）。更换嵌入模型即触发整库重建任务；如果需要不同知识库用不同维度的模型，就要按维度分表，复杂度明显上升。

## 修订（2026-11-05）

见 [ADR-029](ADR-029-design-rulings.md) 第 23 条：统一向量维度；数据源不再指定 Confluence、GitLab，改为通用连接器 SDK 加 HTTP/REST、S3/MinIO/NAS、JDBC 三类通用连接器；`ChangeEvent` 增加可选 `aclPrincipals`，有源权限时检索取知识库级与文档级权限的交集。

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- **自动检索结果的位置**：每轮自动检索的结果放在本轮尾部，不进入历史；回答实际采用的片段以 citation item 留在历史中（[ADR-048](ADR-048-context-engineering.md) 第 1 节）。
- 记忆的本轮检索复用本 ADR 的混合召回组件（[ADR-050](ADR-050-memory-system.md) 第 4.2 节）。记忆与知识库的边界见 ADR-050 第 1 节。

## 修订：文档解析与 OCR（2026-11-06）

见 [ADR-060](ADR-060-document-parsing-service.md) 与 ADR-029 第 71 条：入库流水线第 2 步「解析」改为调用文档解析服务，输入为契约 `tioka.parse/v1`，`DocumentParser` SPI 不再扩展；OCR、图片与扫描件随解析服务在 M4 可用，KNW-09 由 PRS-09 取代。知识库只取 `role = BODY` 的块，按 `parentId` 与 `level` 得到标题路径，块的坐标作为引用定位信息存入分块元数据；切块策略仍由 Knowledge 决定（第 70 条）。

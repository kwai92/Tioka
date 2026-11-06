# Knowledge / RAG 深度调研与外部知识库产品对接

- 日期：2026-11-06
- 来由：负责人提出「针对 Knowledge / RAG 进行深入调研与设计，并考虑采购外部知识库产品的适配与对接设计」
- 对照：[ADR-018](../adr/ADR-018-knowledge-ingest-retrieval.md)、[ADR-036](../adr/ADR-036-business-ontology-domain-pack.md)（文档层）、[ADR-040](../adr/ADR-040-dataset-policy-data-label.md)、[ADR-048](../adr/ADR-048-context-engineering.md)、[ADR-050](../adr/ADR-050-memory-system.md)、ADR-029 第 23、51、52、66、68 条、[M3-06](../construction/M3-06-平台补齐与G3闸门基座.md) 第 2〜4 节、34 号计划 KNW 表
- 落地：按负责人「有选项时按推荐方案直接写设计」的要求，写成 [ADR-055](../adr/ADR-055-knowledge-ingest-chunking.md)、[ADR-056](../adr/ADR-056-retrieval-qa-evaluation.md)、[ADR-057](../adr/ADR-057-external-knowledge-providers.md) 与 ADR-029 第 70 条；备选列在第 9 节供复核
- 分工：文档解析、OCR、版面模型与外部 OCR / 解析产品的深入调研与设计由另一份专题负责（ADR-060 起）；本文第 2 节只做概览，Knowledge 把解析结果当作输入契约（ADR-055 第 1 节）。
- 写法说明：只用文字和表格，不附代码。产品接口字段以 2026-11 查阅的官方文档为准，接入前按所采购版本复核。

---

## 0. 结论摘要

1. **现有代码的「混合检索」其实只有向量召回**。`KnowledgeService.search` 在 pgvector 分支里先取向量近邻，再在这些近邻上算关键词分，关键词召回从未独立执行；向量没找到但字面命中的条款永远召不回来。同时还有固定 200 字切块、每次检索写 citation、只能查单个知识库。ADR-018 已经定了改法，但施工排在 M3 W42–W44，而**记忆（ADR-050）也复用这个召回组件**，所以独立关键词召回要和 KNW-03 一起在 M3 做（KNW-12）。
2. **解析有两套**。Knowledge 自带 `DocumentParser`（PDFBox、POI，只取纯文本），文档层（DOC-01、DOC-02，M3 W27–W29）又做了一套带页、版面块、OCR 合并的解析。第 51 条只规定两者**不共用表**，没有禁止共用解析。建议 Knowledge 入库改用文档层的解析服务，按 ADR-055 第 1 节的输入契约消费解析结果，删掉自带解析器，扫描件与图片从此对知识库也可用。
3. **版面解析是 RAG 质量的第一个决定因素**。OmniDocBench 榜首已经是 1B 以下的小型视觉语言模型（PaddleOCR-VL 系列、MinerU 2.5），可以私有化部署。引擎选型、OCR 与外部解析产品对接由文档解析专题（ADR-060 起）负责，本文只给概览（第 2 节）。
4. **嵌入模型建议定为 1024 维**：公有云部署用百炼 `text-embedding-v4`（支持 64〜2048 维，默认 1024），私有化部署用 `Qwen3-Embedding-0.6B`（原生 1024 维，Apache-2.0）。两者都是 1024 维，换模型只需重嵌入，不改表结构，也远低于 pgvector HNSW 对 `vector` 类型 2000 维的上限。Rerank 用百炼 `qwen3-rerank` 或私有化 `Qwen3-Reranker-0.6B`。这是 2026-11-06 整体规划里的负责人待定项，本文按推荐写入第 70 条，负责人可改。
5. **检索链路补三件事**：多轮对话下的查询改写（把追问改成独立问题）、检索轨迹记录与命中测试、知识使用模式（自动注入、作为工具、两者都开）。Agentic RAG 不另建框架，用 Runtime 的 ReAct 循环加 `kb.*` 工具实现。
6. **GraphRAG 不建议在 M4 做全量实现**。微软 GraphRAG 索引成本高；LazyGraphRAG 的索引成本与向量 RAG 相同，约为完整 GraphRAG 的 0.1%；LightRAG 可以全部放在 PostgreSQL 上。Tioka 的结构化知识已经有业务本体（ADR-036）承担。KNW-10 改为「M4 之后、按评测证明有需要再做」，届时用 LightRAG 式的轻量实体索引放在 PostgreSQL 上。
7. **评测从「只测召回」扩到「召回 + 生成」**：在 EVO-02 评估器 SPI 上加忠实度、引用正确率、无答案拒答三个评估器，并支持从文档自动生成候选评测题（人工审核后入集）。
8. **外部知识库产品的对接方式**：主流产品（Dify、RAGFlow、FastGPT、百炼、Amazon Bedrock）都提供「按知识库 ID + 查询 → 返回片段、分数、元数据」的检索 HTTP 接口，Dify 还定义了一个让外部知识库接入的公开协议（External Knowledge API）。建议以**检索联邦**为主：外部知识库在 Tioka 里登记为一种知识库，内容留在外部，查询时由适配器实时检索，结果和 Tioka 自有知识库一起融合、重排、引用；权限、数据标签、出网、审计、限流都在 Tioka 这一侧执行（ADR-057）。另外提供两种补充方式：内容同步（经连接器 SDK 把外部文档拉进 Tioka 索引）与反向暴露（Tioka 实现同一检索协议和 MCP 工具，让采购的产品也能用 Tioka 的知识库）。
9. **排期**（以第 68、69 条后的计划为基准：M3 W24–W53，M4 W54–约 W69，Foundation F 阶段 W70–W80）：M3 净增后端约 8 人日（KNW-11、KNW-12、KNW-14）、前端 4 人日（FE-32）；M4 净增后端约 21、前端 3 人日（KNW-13、KNW-15、KNW-16、KNW-17、FE-33），M4 延长约 1.5 周到约 W70.5，F 阶段顺延为约 W72–W82；其余（KNW-18、KNW-20、KNW-10）在 M4 之后。若采购的外部知识库要在 M3 就接入，见第 9 节方案 B。

---

## 1. 现状核查

### 1.1 代码（main d39ae91，`tioka-module-knowledge`）

| 环节 | 现状 | 问题 |
| --- | --- | --- |
| 解析 | `DocumentParser`：PDF 用 PDFBox 取文字层，DOCX 用 POI 取段落，MD、HTML、TXT 做文本规范化 | 输出纯文本，丢失标题层级、表格结构、页码；扫描件、图片、Excel、PPT 不支持 |
| 切块 | 固定 200 字符，无重叠 | 句子与表格被切断；没有标题上下文 |
| 嵌入 | `OpenAiCompatibleEmbeddingClient`，缺省 `HashEmbeddingClient` | 绕过 Model Hub；M1 的 KNW-01 已安排改造 |
| 入库 | 同步执行，嵌入失败整篇失败 | M3 KNW-05 异步化 |
| 检索 | pgvector 分支：取向量近邻（topK×5，至少 20 条），在这些近邻上算关键词分，两路 RRF | **关键词召回不是独立的一路**，只是对向量结果的再排序；本地分支全表加载 |
| 范围 | 一次只能查一个 `kbId` | Agent 绑定多个知识库时要调多次 |
| 引用 | 每次检索为每条结果写一行 `kb_citation` | 检索越多表越大；ADR-018 已定改为回答采用时才写 |
| 工具 | `kb.search`、`kb.read_chunk`、`kb.read_document` | 没有列出知识库、按父块读取、按元数据过滤 |
| 评测 | `EvalService` 内存计算命中率 | M3 KNW-07 并入 EVO-02 |
| Wiki、数据源 | `WikiService` 只做页面存取；`DataSourceService` 只记录 URI | KNW-06（M4 前段）按连接器 SDK 重做 |

### 1.2 已有设计

| 设计 | 已定内容 | 本次调研后的判断 |
| --- | --- | --- |
| ADR-018 | 异步入库 + 版本切换；父子块；向量 + 全文 RRF；Rerank；ACL 下推；数据源 SPI；评测进 CI | 方向正确，保留；补解析、查询改写、使用模式、生成侧评测、外部知识库 |
| 第 23 条 | 统一向量维度；通用连接器 SDK；`aclPrincipals` 文档级权限 | 保留；本文给出维度与模型的推荐 |
| 第 51 条 | 合同原文走文档层，不进 Knowledge 切块 | 保留表的隔离；解析服务共用（ADR-055） |
| 第 52 条 | OCR 作为 Model Hub 能力 `ocr`，DOC-02 在 M3 | 保留；Knowledge 复用 |
| ADR-040 | 检索 = 知识库 ACL ∩ 文档 ACL ∩ 授予资源 ∩ `maxDataLabel` | 保留；外部知识库沿用同一公式（第 7 节） |
| ADR-048 | 知识自动检索结果在本轮尾部（D 区），不进历史 | 保留；本文补 D 区知识段的预算 |
| ADR-050 | 记忆的本轮检索复用知识的混合召回组件 | 说明独立关键词召回（KNW-12）不能晚于 MEM-01 |
| S4 | 对标 WeKnora 的能力地图（多引擎解析、多数据源、GraphRAG、Wiki、对外 MCP 等） | 作为远期能力全集；本文只把有验收价值的部分排进 M3〜M4 |

---

## 2. 文档解析

### 2.1 技术路线

| 路线 | 代表 | 特点 |
| --- | --- | --- |
| 规则与文字层 | Apache PDFBox、POI、Tika | 快、零成本；扫描件无能为力；表格、多栏版面容易乱序 |
| 版面分析流水线 | PaddleOCR PP-StructureV3、RAGFlow DeepDoc、Docling | 版面检测 + OCR + 表格识别分模型组合；可 CPU 运行，精度中等 |
| 端到端视觉语言模型 | PaddleOCR-VL（0.9B）、MinerU 2.5（1.2B）、GLM-OCR（0.9B）、dots.ocr（3B） | 直接从页面图像输出 Markdown / JSON（含表格、公式、阅读顺序）；需要 GPU，精度最高 |
| 通用多模态大模型 | 通义千问 VL、GPT 系列 | 效果好但贵，适合少量疑难页的兜底 |
| 云端解析服务 | 百炼文档解析、各云厂商 OCR | 无需部署；文档出网，受数据标签约束 |

### 2.2 公开评测

OmniDocBench（OpenDataLab）是目前中英文文档解析最常用的端到端基准，综合分 = 文本编辑距离、表格 TEDS、公式 CDM 三者的平均。v1.6 全量榜前列：PaddleOCR-VL-1.6（0.9B）96.34、MinerU2.5-Pro（1.2B）95.75、GLM-OCR（0.9B）95.22、PaddleOCR-VL-1.5 94.93、PaddleOCR-VL（0.9B）94.18。传统流水线工具（如 Marker）明显落后。这个榜由 MinerU 所在团队维护，读数需要在自己的样本上复核。

### 2.3 许可与部署

| 引擎 | 许可 | 部署 | 对 Tioka 的影响 |
| --- | --- | --- | --- |
| PaddleOCR（含 PaddleOCR-VL、PP-StructureV3） | Apache-2.0 | 提供 Docker 服务化部署、OpenAI 兼容的推理服务；支持 NVIDIA、Intel CPU、昆仑芯等 | 可随私有化安装包分发；国产硬件友好 |
| MinerU | 「MinerU 开源许可」：Apache-2.0 加附加条款，商用无需另购许可，但集团合并月活超 1 亿或月收入超 2000 万美元需另行授权，且对外提供在线服务时须在界面或公开文档标明使用了 MinerU | Python 服务 | 对单企业部署基本无影响，但要在交付文档中声明 |
| 百炼文档解析与 OCR | 商业云服务 | 无需部署 | 只能处理数据标签允许出网的文档 |

### 2.4 对 Tioka 的含义

- 引擎选型、部署形态、OCR 合并与外部解析产品对接交给文档解析专题（ADR-060 起）。
- Knowledge 只依赖一份「结构化文档」：页、版面块（标题、段落、列表、表格、图、公式、页眉页脚）、阅读顺序、标题路径、位置框、置信度。必需与可选字段见 ADR-055 第 1 节；解析服务内部怎么得到这些字段对 Knowledge 透明。
- 文档层与 Knowledge 消费同一份解析结果，各自切分、各自存表（第 51 条不变）。

---

## 3. 切块与索引增强

| 方法 | 做法 | 证据 | 建议 |
| --- | --- | --- | --- |
| 结构切块 + 父子块 | 按标题切父块，父块内切子块；检索子块，送父块给模型 | Dify、RAGFlow、WeKnora 都已支持父子块 | ADR-018 已定，保留 |
| 标题路径前缀 | 嵌入与全文索引时在子块前加「文档标题 > 章 > 节」 | 零成本，解决「本条款」「上述金额」这类缺上下文的块 | 默认开启（ADR-055） |
| 表格专门处理 | 小表整表成块；大表按行组切块，每块重复表头 | 表格被切断是 RAG 错误的常见来源 | 默认开启 |
| 上下文增强（Contextual Retrieval） | 用模型为每个块生成 50〜100 token 的上下文说明，再嵌入与建全文索引 | Anthropic 报告：只做上下文嵌入，检索失败率降 35%；再加上下文 BM25 降 49%；再加 Rerank 降 67%。借助提示缓存，生成成本约每百万文档 token 1.02 美元 | 按知识库可选，默认关闭（M4 之后，KNW-20）；先用标题路径前缀拿到大部分收益 |
| 问答对与摘要索引 | 为块生成可能的问题或摘要，作为额外索引 | FastGPT 支持自定义索引；成本高 | 不做；FAQ 类知识库直接按问答对入库 |
| 多向量、稀疏向量 | 同一块存稠密 + 稀疏向量 | `text-embedding-v4` 经 DashScope 接口可输出稀疏向量 | 不做；PostgreSQL 全文索引承担关键词一路 |

---

## 4. 嵌入、Rerank 与索引

### 4.1 嵌入模型

| 模型 | 维度 | 上下文 | 许可 / 形态 | 备注 |
| --- | --- | --- | --- | --- |
| 百炼 `text-embedding-v4` | 2048、1536、1024（默认）、768、512、256、128、64 | 单条 8192 token；每批最多 10 条 | 云 API（OpenAI 兼容与 DashScope 两种接口） | 稀疏向量只在 DashScope 接口提供 |
| `Qwen3-Embedding` 0.6B / 4B / 8B | 1024 / 2560 / 4096，支持 MRL 自定义维度 | 32K | Apache-2.0，可私有化 | 8B 于 2025-06 位列 MTEB 多语言榜第一（70.58） |
| `bge-m3` | 1024 | 8192 | MIT | 稠密 + 稀疏 + 多向量；老牌基线 |
| `bge-small-zh` | 512 | 512 | MIT | 当前 CI 用的本地小模型 |

**推荐**：部署统一 1024 维。公有云默认 `text-embedding-v4`（1024），私有化默认 `Qwen3-Embedding-0.6B`（1024）；对精度要求更高且有 GPU 的部署可用 `Qwen3-Embedding-4B` 以 MRL 截到 1024。CI 改用 `Qwen3-Embedding-0.6B` 的 CPU 推理，使基线与线上同族；若 CI 资源不够，保留 `bge-small-zh`（CI 部署单独配置 512 维），只是基线对线上的指示意义变弱。

### 4.2 Rerank

Qwen 官方报告（召回取 Qwen3-Embedding-0.6B 的前 100 条）：中文 CMTEB-R 上 Qwen3-Reranker-8B 77.45、gte-multilingual-reranker-base 74.08、bge-reranker-v2-m3 72.16。百炼知识库检索接口内置 `qwen3-rerank`、`gte-rerank` 等。**推荐**：公有云 `qwen3-rerank`，私有化 `Qwen3-Reranker-0.6B`，CI 用同一 0.6B。

### 4.3 索引存储

| 选项 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| PostgreSQL：pgvector + 内置全文 + 中文分词扩展 | 与业务库同一事务，ACL 可 SQL 下推；运维最简单 | 内置 `ts_rank` 不是 BM25；中文分词要扩展 | **默认**。pgvector HNSW 支持 `vector` 2000 维、`halfvec` 4000 维；0.8 起支持迭代索引扫描，过滤后结果不够时自动继续扫描，正好解决「ACL 过滤后召回不足」 |
| ParadeDB `pg_search`（Tantivy BM25） | 真 BM25、混合检索都在 PostgreSQL 内 | 社区版 AGPL-3.0，私有化分发有许可风险；中文分词支持需另行确认 | 不作为默认 |
| OpenSearch / Elasticsearch | 成熟 BM25 与中文分析器；大规模 | 多一套集群；ACL 要另行同步 | 预留检索索引 SPI，单部署超过约 500 万子块时再引入 |

中文分词：`pg_jieba`（BSD-3-Clause，提供 `jiebacfg`、`jiebaqry` 等配置，支持自定义词典）或 `zhparser`。装不了扩展的环境按 ADR-018 退回应用层分词后写 `tsvector`。

---

## 5. 检索与问答

### 5.1 查询侧技术

| 技术 | 做法 | 建议 |
| --- | --- | --- |
| 多轮查询改写 | 结合最近几轮把追问改写为独立问题 | **必做**：多轮对话里「那违约金呢」直接检索几乎必然失败；用小模型，结果写入检索轨迹 |
| 多查询扩展 | 生成 2〜3 个改写并分别召回后融合 | 按知识库可选，默认关 |
| HyDE | 先让模型写假想答案再用其向量检索 | 不做；对中文制度类文档收益不稳定，且多一次生成延迟 |
| 元数据过滤抽取 | 从问题中抽出年份、部门等过滤条件 | 只支持调用方显式传入的过滤（工具参数、Flow 节点配置），不做自动抽取 |
| 分数阈值与无答案 | Rerank 分低于阈值时视为没检索到 | 必做；无命中时提示模型说明「知识库中没有找到」，不是凭空作答 |

### 5.2 两种使用模式

| 模式 | 业界对应 | 适合 |
| --- | --- | --- |
| 自动注入（AUTO） | 「快速 RAG」：每轮检索结果放入本轮尾部 | 问答型 Agent、IM 机器人 |
| 作为工具（TOOL） | Agentic RAG：模型自己决定何时检索、检索什么、是否再读全文 | 多步任务、研究型问题、多跳问题 |
| 两者（BOTH） | 先自动注入，模型不够时再调工具 | 通用助手 |

Self-RAG、CRAG 这类「检索 → 评估 → 再检索」的循环，在 Tioka 里就是 ReAct 循环加 `kb.*` 工具，加上 ADR-046 的执行策略，不需要另建一套 RAG 框架。

### 5.3 GraphRAG

| 方案 | 索引成本 | 适合 | 备注 |
| --- | --- | --- | --- |
| 微软 GraphRAG | 高：全量抽取实体关系并生成社区摘要 | 全局性问题（「这批合同的主要风险有哪些」） | 文档更新要重算社区 |
| LazyGraphRAG | 与向量 RAG 相同，约为完整 GraphRAG 的 0.1% | 同上，查询时按需构图 | 微软报告：全局查询质量与 GraphRAG 相当，查询成本低 700 倍以上 |
| LightRAG | 中：抽实体关系，不做社区报告 | 局部 + 全局混合；支持增量与删除 | MIT；存储可以全部用 PostgreSQL |
| RAGFlow RAPTOR | 中：递归聚类摘要 | 长文档总结 | — |

Tioka 的情况：合同与业务对象已经由业务本体（ADR-036）结构化，「这批合同的风险」走 `rule.evaluate` 与读模型工具比 GraphRAG 更准；参考资料类知识库目前规模小（每天约 10 份合同对应的修订案例与制度）。**建议**：KNW-10 移到 M4 之后，先用评测集证明「全局性问题」在现有链路上答不好再做，届时选 LightRAG 式实体索引放在 PostgreSQL。

---

## 6. 评测

| 层 | 指标 | 方法 |
| --- | --- | --- |
| 检索 | Recall@5、MRR（已定），加 nDCG@10、无答案题的空召回率 | 评测集「问题 + 期望命中的文档或块」 |
| 生成 | 忠实度（回答每句话是否有检索内容支持）、引用正确率（引用的块是否支持该句）、回答相关性、无答案拒答率 | 模型打分评估器；对应 Ragas 的 Faithfulness、Response Relevancy、Context Precision / Recall 与 NVIDIA 的 Response Groundedness |
| 线上 | 无命中率、低分率、点踩与检索轨迹关联、引用点击率 | GOV-13 统计；EVO-01 反馈 |

评测集来源：人工标注为主；可以用模型从文档自动生成候选问题与期望块，人工审核后入集（避免模型自问自答导致指标虚高）。外部知识库可以用同一评测集评测（第 7.5 节），作为采购选型的依据。

---

## 7. 外部知识库产品的对接

### 7.1 各产品的接口

| 产品 | 检索接口 | 鉴权 | 检索参数 | 返回 | 权限与过滤 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| Dify（知识库 API） | POST `/datasets/{dataset_id}/retrieve` | 知识库 API Key（Bearer），可限定到单个知识库 | `query`（≤250 字）、检索方式（关键词、语义、全文、混合）、Rerank 开关与模型、权重、`top_k`、分数阈值、元数据过滤条件 | 分段内容、分数、文档 ID 与名称、文档元数据、子块 | 只有 Key 级范围；元数据过滤 | 有错误码：401、403（未开放 API、超频、Key 不属于该库）、404 |
| Dify External Knowledge API（外部知识库协议） | Dify 调用方 POST `{endpoint}/retrieval` | Bearer API Key | `knowledge_id`、`query`、`retrieval_setting`（`top_k`、`score_threshold`）、可选 `metadata_condition`（与 / 或，等于、包含、大于、之前之后等） | `records[]`：`content`、`score`（0〜1）、`title`、`metadata` | 协议本身不带用户身份 | **事实上的通用检索协议**：任何系统实现它就能被 Dify 当知识库用；Tioka 可以用它对接实现了该协议的产品，也可以自己实现它被别人对接 |
| RAGFlow | POST `/api/v1/retrieval` | Bearer API Key | `question`、`dataset_ids`、`document_ids`、分页、`similarity_threshold`、`vector_similarity_weight`、`top_k`、`rerank_id`、关键词增强、高亮 | `chunks[]`：内容、文档 ID、总相似度、向量相似度、词项相似度、位置、知识库 ID；文档聚合 | 知识库可设「仅自己 / 团队」；文档 ID 过滤 | 上传与解析接口分开（上传后要单独触发解析） |
| FastGPT | POST `/api/core/dataset/searchTest` | Bearer API Key | `datasetId`、`text`、`limit`（按 token 计，最大 20000）、`similarity`、检索模式（向量、全文、混合）、Rerank、问题扩展 | `q`、`a`、集合 ID、来源名、分数 | 列表接口带权限对象；检索无元数据过滤 | 文档导航注明「知识库接口不再维护」，对接需按采购版本确认 |
| 阿里云百炼 | POST `/{WorkspaceId}/index/retrieve`（OpenAPI，AccessKey + RAM `sfm:Retrieve`）；另有 DashScope 风格的知识检索接口（API Key） | 阿里云 AccessKey 或 DashScope API Key | `IndexId`、`Query`、稠密 / 稀疏召回条数（各 ≤100，合计 ≤200）、Rerank（`qwen3-rerank` 等，TopN ≤20）、查询改写与历史、`SearchFilters` | `Nodes[]`：文本、分数（0〜1）、元数据（文档 ID、文档名、标题、图片地址） | RAM 授权到工作空间；元数据与标签过滤 | 多知识库检索最多 15 个；官方提示接口延迟较高 |
| Amazon Bedrock 知识库 | POST `/knowledgebases/{id}/retrieve` | AWS 签名 | 查询文本或图片、返回条数、元数据过滤（等于、大于、in、前缀、与、或）、Rerank 配置、护栏 | 内容、位置、文档 ID、元数据、分数 | 元数据过滤；`userContext` | 只在海外部署相关 |
| 扣子（Coze） | 公开 OpenAPI 以知识库与文档管理为主；本次未能从官方文档确认有独立的检索接口（文档页为前端渲染，抓取失败） | 个人访问令牌 / OAuth | — | — | — | 采购前要求厂商提供检索接口或 MCP；否则只能经其 Bot 对话接口间接使用，不建议 |
| 腾讯 WeKnora | REST API 与官方 MCP Server（约 29 个工具）；API Key 可限定能力与知识库 | API Key、OIDC、MCP OAuth2 | — | — | 四级角色 | 可经 MCP 适配器接入 |
| MaxKB | 有 API 与 MCP 工具调用；本次未查到检索接口细节 | — | — | — | — | GPL-3.0；按采购版本确认 |

### 7.2 共性与差异

- **共性**：都能做到「知识库 ID + 查询 + 条数 + 阈值 → 片段文本 + 分数 + 文档标识 + 元数据」。这就是适配器的最小公约数。
- **分数不可比**：Dify、百炼给 0〜1，RAGFlow 给加权相似度，FastGPT 按检索模式给不同含义的分数。多个来源混合时不能直接按分数排序。
- **权限只到 Key 或知识库**：没有一家的检索接口接受「当前最终用户是谁」并按文档 ACL 过滤（Bedrock 的 `userContext` 不是 ACL）。文档级权限只能靠元数据过滤模拟，前提是对方知识库里的文档带了权限元数据。
- **内容会变**：外部文档被修改或删除时，Tioka 侧的引用会失效。
- **接口稳定性不一**：FastGPT 已声明不再维护知识库接口；百炼有新旧两套接口；Dify 的 API 随版本有字段增减。

### 7.3 三种对接方式

| 方式 | 内容在哪 | 检索谁做 | 适合 | 代价 |
| --- | --- | --- | --- | --- |
| **检索联邦**（推荐默认） | 外部产品 | 外部产品 | 已采购、已有人在维护内容的知识库；不想复制数据 | 检索质量由外部决定；权限只能到知识库级（或元数据）；多一次网络往返 |
| 内容同步 | 复制到 Tioka | Tioka | 外部产品只是内容仓库，或需要文档级 ACL、统一评测 | 外部要有文档导出接口；重复存储与嵌入成本；同步延迟 |
| 反向暴露 | Tioka | Tioka | 已采购的产品（如 Dify 应用）想用 Tioka 的知识库 | Tioka 要对外开放检索接口，按服务身份鉴权 |

### 7.4 检索联邦的关键设计点

1. **外部知识库是一种知识库**：Agent、Flow、Chat 绑定的知识库列表里可以混合 Tioka 自有知识库与外部知识库，调用方不感知差别。
2. **融合**：各来源并行召回，按名次做 RRF 融合（不比较原始分数），再用 Tioka 绑定的 Rerank 模型统一重排；外部来源的分数阈值按来源各自配置。
3. **权限**：知识库级 ACL、授予资源、`maxDataLabel` 都在 Tioka 侧判定，规则与自有知识库相同；外部知识库的数据标签由管理员登记（缺省 `INTERNAL`）。文档级权限只有在外部产品支持元数据过滤、且文档带权限元数据时才启用，否则明确标注「仅知识库级权限」。
4. **出网与数据标签**：查询文本会发到外部产品，所以外部知识库实例要登记「允许接收的最高数据标签」，会话标签高于它时不查该来源，与 DAT-04 按标签选模同一规则；调用一律经 EgressGuard。
5. **引用**：外部片段在被回答采用时，把片段文本、来源 URL、外部文档 ID 与内容哈希一起写进 citation item，原文变化或删除后仍能展示当时的依据，并提示「来源已更新」（与第 12 条一致）。
6. **可靠性**：每个实例单独超时（缺省 3 秒）、令牌桶限流、熔断；一个来源失败不影响其他来源，回答里注明该来源本次未参与。
7. **审计与成本**：每次调用记录实例、知识库、条数、耗时、结果数与查询哈希；外部产品自己的计费在 Tioka 之外，Tioka 只统计调用次数。

### 7.5 采购选型评测

同一评测集可以分别跑 Tioka 自有知识库与外部知识库（相同文档各自入库），输出 Recall@5、MRR、忠实度、引用正确率与延迟。这让「买哪家」「买了之后要不要同步到 Tioka」有数据依据。

---

## 8. 差距汇总

| # | 差距 | 影响 | 处理 |
| --- | --- | --- | --- |
| G1 | 关键词召回不独立；单知识库检索 | 字面命中的条款召不回；记忆检索同样受影响 | KNW-12（M3） |
| G2 | 两套解析；Knowledge 不支持扫描件、图片、表格结构 | 知识库里的扫描制度文件无法检索 | KNW-11（M3）复用文档层解析服务；引擎增强由文档解析专题负责 |
| G3 | 嵌入、Rerank 模型未定 | KNW-07 基线、G3 闸门没有依据 | 第 70 条推荐 1024 维与两套默认模型 |
| G4 | 无查询改写；无检索轨迹 | 多轮追问检索失败；出错无法定位 | KNW-13（M4） |
| G5 | 知识使用模式未定义 | Agent 只能自动注入或只能调工具，二选一写死 | KNW-14（M3） |
| G6 | 评测只测召回 | 回答编造或引用错误测不出来 | KNW-15（M4） |
| G7 | 没有外部知识库接入设计 | 已采购产品无法被 Agent 使用；无法评测选型 | KNW-16、KNW-17（M4），KNW-18 反向暴露（M4 之后） |
| G8 | GraphRAG 排在 M4 但缺少需求证据 | 投入大、收益不确定 | KNW-10 移到 M4 之后，按评测触发 |

---

## 9. 选项与推荐

### 选项 P：解析

- **P1（推荐）**：Knowledge 复用文档层解析服务，按输入契约消费；引擎由文档解析专题定。
- P2：Knowledge 保留自带解析器，只补 OCR。少 3 人日，但两套解析长期分叉，表格与版面问题在知识库里一直存在。

### 选项 Q：检索

- **Q1（推荐）**：PostgreSQL 独立两路召回 + RRF + Rerank + 父块扩展；多知识库一次查询；查询改写；三种使用模式。
- Q2：引入 OpenSearch 作为检索引擎。BM25 更标准，但多一套集群与 ACL 同步，当前规模不需要。

### 选项 R：外部知识库

- **R1（推荐）**：`KnowledgeProvider` SPI，检索联邦为主；内置 Dify、RAGFlow、百炼、通用检索协议（Dify External Knowledge API 形态）、MCP 五种适配器；FastGPT、Bedrock、扣子等按需以插件提供；内容同步走 KNW-06 的连接器；反向暴露在 M4 之后。
- R2：只做内容同步，不做实时联邦。权限与评测完全统一，但要求每个产品都有导出接口，且要二次存储和嵌入。
- R3：只经 MCP 接入（外部产品提供 MCP 检索工具，Agent 直接调用）。实现最省，但结果不进融合与重排，权限、标签、引用都要各自处理。可作为 R1 的一种适配器保留。

### 排期方案

| 方案 | 内容 | M3 | M4 | 代价 |
| --- | --- | --- | --- | --- |
| **A（推荐，已按此写）** | M3：KNW-11、KNW-12、KNW-14、FE-32；M4：KNW-13、KNW-15、KNW-16、KNW-17、FE-33；M4 之后：KNW-18、KNW-20、KNW-10 | 后端 +8，在 BE-C 的 W48 缓冲与 G3 修复周内吸收，不延长 | 后端 +21、前端 +3，延长约 1.5 周 | 外部知识库要到 M4 前段才能接入 |
| B 外部知识库提前 | 在 A 的基础上把 KNW-16 与 KNW-17 的 Dify / 百炼 / 通用协议三种适配器提前到 M3 | 后端 +20，延长约 1 周 | 后端 +9，延长约 0.5 周 | 适合已签采购合同、M3 就要上线的情况 |
| C 最小 | 只做 KNW-12 与 KNW-11（M3）；其余全部放 M4 之后 | 后端 +6 | 不变 | 外部知识库、查询改写、生成评测都推迟到 M4 之后 |

---

## 10. 排期影响（方案 A）

| 里程碑 | 新增 | 后端 | 前端 | 说明 |
| --- | --- | --- | --- | --- |
| M3 | KNW-11、KNW-12、KNW-14、FE-32 | 8 | 4 | BE-C 在 W42–W46 内做，占用 W48 缓冲（W49–W53 的缓冲已被第 69 条的 BIZ-01 增量占用一部分，G3 修复余量相应变小）；FE 不是瓶颈 |
| M4 | KNW-13、KNW-15、KNW-16、KNW-17、FE-33 | 21 | 3 | 按 3 名后端约 1.5 周；M4 由约 W69 到约 W70.5，F 阶段顺延约 1.5 周 |
| M4 之后 | KNW-18、KNW-20、KNW-10 | 约 10（KNW-10 未估） | 约 2 | 增强批次 |

KNW-09 的剩余部分（图片理解、Excel）交给文档解析专题，KNW-09 在 34 号计划中标为作废、由该专题的工作项取代。

---

## 参考资料

- Dify External Knowledge API：https://docs.dify.ai/en/use-dify/knowledge/external-knowledge-api.md
- Dify 知识库检索接口：https://docs.dify.ai/zh/api-reference/knowledge-bases/retrieve-chunks-from-a-knowledge-base-test-retrieval.md
- Dify 知识库概览：https://docs.dify.ai/en/use-dify/knowledge/readme
- RAGFlow HTTP API（Hugging Face 上的文档镜像，提交 fc803e8）：https://huggingface.co/spaces/retopara/ragflow/blob/fc803e8498667c3e2fc233e70292c9743c7934c3/docs/references/http_api_reference.md
- FastGPT 知识库 OpenAPI：https://doc.fastgpt.io/openapi/dataset
- 阿里云百炼 Retrieve：https://help.aliyun.com/zh/model-studio/api-bailian-2023-12-29-retrieve
- 阿里云百炼知识库：https://help.aliyun.com/zh/model-studio/rag-knowledge-base
- 阿里云百炼文本向量 `text-embedding-v4`：https://www.alibabacloud.com/help/zh/model-studio/text-embedding-synchronous-api
- Amazon Bedrock Retrieve：https://docs.aws.amazon.com/bedrock/latest/APIReference/API_agent-runtime_Retrieve.html
- 扣子开放平台知识库文档（抓取失败，未验证）：https://www.coze.cn/open/docs/developer_guides/knowledge_overview
- Tencent WeKnora：https://github.com/Tencent/WeKnora
- MaxKB：https://github.com/1Panel-dev/MaxKB
- Anthropic，Contextual Retrieval：https://www.anthropic.com/news/contextual-retrieval
- Qwen3 Embedding：https://qwenlm.github.io/blog/qwen3-embedding ；论文 https://arxiv.org/abs/2506.05176
- OmniDocBench：https://github.com/OpenDataLab/OmniDocBench
- PaddleOCR-VL 论文：https://arxiv.org/abs/2510.14528 ；PaddleOCR：https://github.com/PaddlePaddle/PaddleOCR
- MinerU 许可：https://github.com/opendatalab/MinerU/blob/master/LICENSE.md
- LightRAG：https://github.com/HKUDS/LightRAG
- LazyGraphRAG：https://www.microsoft.com/en-us/research/blog/lazygraphrag-setting-a-new-standard-for-quality-and-cost/
- Ragas 指标：https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/
- pgvector：https://github.com/pgvector/pgvector
- ParadeDB：https://github.com/paradedb/paradedb
- pg_jieba：https://github.com/jaiminpan/pg_jieba

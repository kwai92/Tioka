# M3 批次 C-3：文档层（DOC-01/02/03 + PRS-00 契约核心子集）实现覆盖说明

> 批次：M3 批次 C-3（M3-03 §1 V170 L44-104、§2 DOC-01 L224-269、§3 DOC-02 L273-300、§4 DOC-03 L304-309；ADR-060 `tioka.parse/v1`；ADR-061 模型引擎适配器；ADR-029 第 71 条）
> 分支：`feature/m3-c3-doc`（基线 main = `7d95c03`，worktree `p3-c3-doc`；主仓库 main 已前进至 3ae54ee，未 rebase、未触碰，合并由 team-lead 处理）
> 收口范围：① V170 迁移（doc_document/doc_page/doc_unit/doc_clause 四表）+ DDL 契约副本；② ADR-060 契约核心子集（页/块/条款树三类，docparse 子包）；③ 解析管线（Docx/Pdf/Doc/Image 四 Parser + ParserRouter）；④ OCR 与合并（ModelGateway.ocr 链路 + OcrMerger IoU 规则）；⑤ `@doc.unit` 工具 + EvidenceBinder 三错误码引文校验
> 硬约束遵守：不改 deploy/compose.yml；MQ 信封契约不动（DOCUMENT_PARSED 复用 OntologyMqTopics 已有常量）；workspaceId 权威（V170 space_id 列语义映射 workspace_id，注释标明）；业务 ID 用 Ids.newId；ArchUnit 既有规则全过；M3-00/M3-03 文档本体未改

## 一、范围 → 实现映射

| 项 | 实现 |
|------|------|
| V170 迁移 | `db/migration/V170__m3_document_layer.sql`：四表 DDL 照 M3-03 §1 抄录 + `idx_doc_unit_doc_seq`；契约副本同步 `docs/contracts/db/V170__m3_document_layer.sql`（out-of-order 已开启，当前最高 V222，V170 空号可用） |
| ADR-060 契约核心子集 | ontology-api 新建 `docparse` 子包（M4 迁独立模块，登记偏差）：`ParseContract`（CONTRACT=`tioka.parse/v1` + 块类型/角色/来源/页类型/状态/flags 常量）、`ParsedRegion`（含 `iou()`）、`ParsedTableRef`、`ParsedLine`、`ParsedUnit`、`ParsedClause`、`ParseResult`；`api/doc/DocUnit` 重写为 8 参（补 pageNo/clausePath/normText）、`FileRef` 扩为 8 参（补 spaceId/dataLabel/sourceSystem/sourceRef，保留 4 参兼容构造器） |
| DOC-01 解析管线 | `DocxParser`（POI 5.2.5：numbering.xml 自动编号渲染（decimal/chineseCounting/letter/roman 计数器 + %n 展开）、表格按单元格输出（vMerge CONTINUE 跳过）、修订采纳（w:del 排除 + FLAG_HAS_REVISIONS）、批注分离 COMMENT 行、WORD_PAGE_NO=1）；`PdfParser`（PDFBox 2.0.32：自定义 PDFTextStripper 行级 bbox 收集、密度 <50 字/页或乱码率 >20% 判 SCANNED 进 OCR 队列、PDFRenderer 200 DPI 页渲染）；`DocParser`（LibreOffice headless 转 docx 复用，环境依赖登记）；`ImageParser`（整页 OCR）；`DefaultParserRouter` 按 MIME/扩展名路由；`UnitSegmenter`（三类标题正则、5 种空白写法、签章区、section 切换、跨页段落合并）；`ClauseTreeBuilder`（条款树 + pathBySeq 内层覆盖 + 嵌套区间含子条款）；`DocumentService`（dedup file_hash+PARSER_VERSION+spaceId、状态机 PENDING/PARSING/READY/FAILED、一事务批量写四表、DOCUMENT_READY + DOCUMENT_PARSED 经 outbox 发布） |
| DOC-02 OCR 与合并 | `model-api`：`OcrRequest`/`OcrResult`（含 OcrLine）+ `ModelGateway.ocr()` + `ModelPurpose.OCR`；`model-biz`：`OcrEngine` 引擎接口 + `BailianOcrAdapter`（经 EgressClient，OpenAI 兼容 vision 请求，endpoint/model 配置化，未配置抛 ocr_unavailable）+ `DefaultModelGateway.ocr`（precheck→resolve→budget→rateLimit→engine→reconcileTpm→account，图像 token 估算 len/1024/3）；ontology-biz `ModelGatewayOcrClient` 接网关链路；`OcrMerger`（bbox IoU≥0.5 配对用文字层、OCR 独有行 MERGED 追加、置信度 <0.8 随行携带落 ocr_conf） |
| DOC-03 `@doc.unit` + 引文校验 | `DocToolProvider`（ToolProvider 注册表：unitId/before/after=2、Reversibility.NONE、经 DocumentApi.units 取数）；`EvidenceBinder.check(unitId, quote, visibleDocIds)` 三错误码：UNIT_NOT_FOUND / UNIT_NOT_VISIBLE / QUOTE_MISMATCH（表格同行相邻单元格拼接匹配） |
| 装配 | `OntologyDocAutoConfiguration`（`tioka.ontology.doc.enabled=true` 才注册，默认关闭）+ AutoConfiguration.imports 追加；`DocumentRepository` 接口 + `InMemoryDocumentRepository`（persistParsed 单锁段模拟一事务；MyBatis 持久化登记偏差） |

## 二、测试清单（surefire 实证数字）

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `UnitSegmenterTest`（ontology-biz，新增） | 8/8 | 三类标题命中、5 种空白写法、签章区、section 切换（定义/专用/通用/附件）、跨页段落合并、表格行列 |
| `ClauseTreeBuilderTest`（ontology-biz，新增） | 3/3 | 三类 path、嵌套子条款计入父区间（4.1.1 区间含 4.1.2）、BLANK/MODIFIED 状态 |
| `OcrMergerTest`（ontology-biz，新增） | 5/5 | IoU≥0.5 配对、OCR 独有 MERGED 追加、阅读序排序、空文字层、置信度携带 |
| `DocxParserTest`（ontology-biz，新增） | 5/5 | 自动编号渲染（chineseCounting）、表格按单元格、修订排除、批注 COMMENT、普通段落 |
| `EvidenceBinderTest`（ontology-biz，新增） | 6/6 | 三错误码各正反用例 + 表格单元格拼接匹配 |
| `DocToolProviderTest`（ontology-biz，新增） | 4/4 | unitId 取数、before/after 窗口、NONE 可逆性、NOT_FOUND |
| `DocumentServiceIT`（ontology-biz，新增，`-Dtest` 显式跑） | 6 用例：5 过 1 跳过 | dedup 同文件只解析一次（计数器=1）、docx 条款树 + 两事件发布、文字型 PDF 零 OCR 调用（density=119 ≥50 判 TEXT_PDF）、扫描 PDF OCR 桩 READY + OCR 行合并（低置信携带）、OCR 不可用 → FAILED 带 ocr_unavailable、threeFormats @Disabled（Q8 外部输入登记偏差） |
| `OcrRoutingIT`（model-biz，新增，`-Dtest` 显式跑） | 2/2 | CONFIDENTIAL OCR 经网关（dataLabel/spaceId 随请求传递、bindingId 目的地、qwen-vl-ocr 模型、usage SUCCESS 记账）；ocr_unavailable → DEPENDENCY 透传 |
| ontology-biz 模块全量（surefire 默认，`-am` 联动） | **46/46 BUILD SUCCESS** | 新增 31（8+3+5+5+6+4=31 不含 IT）+ 既有 15（SchemaDeriver 1/PathCatalogBuilder 2/DefinitionService 7/SchemaRegistry 5 等） |
| model-biz 模块全量（`-am` 联动） | **全绿 BUILD SUCCESS** | DefaultModelGatewayRerank 4 / ModelBudgetGuard 7 / RetryBackoff 4 / RoutingGatewayIntegration 3 / StructuredRequestBuilder 6 / UsageRecorderCost 1 / HeuristicRouting 5 / RoutingPlanSpecParser 6 / ModelHubSpecs 4 / ProviderClientRerank 4 / ProviderClientStream 1 / ModelRouterFreeze 2 |
| knowledge-biz 回归（`-am` 联动） | **全绿 BUILD SUCCESS** | ModelGateway 加 ocr() 后匿名实现已补（KnowledgeInMemorySupport），既有 9+ 全过 |
| tioka-server 架构契约八套（`-Dtest` 显式） | **59/59** | PersistenceArch 6 / TiokaArchitecture 11 / M3Architecture 10 / M2Architecture 5 / ModuleDependencyArch 5 / RuntimeApiContract 9 / M2B4VaultContract 6 / M3C1aMigrationContract 7 |

## 三、实测登记（坑与修复）

1. **PDFTextStripper 重写 writeString 必须回调 super**：自定义 LineCollector 只把文本 append 进自己的 StringBuilder 而不写入 stripper 内部输出流时，`getText()` 返回空串 → 密度恒 0 → 所有页误判 SCANNED。修复：writeString/writeLineSeparator/writeParagraphSeparator 内回调 `super.xxx(...)`（DocumentServiceIT.textPdfParsesWithTextLayerNoOcr 失败根因）。
2. **POI 两参 `addNum(numId, abstractNumId)` 注册后 `getNum(numId)` 查不到**：须用单参 `addNum(abstractNumId)`（独立 DebugNum 程序实证）。DocxParserTest 据此生成编号定义。
3. **POI `run.text()` 包含 w:delText**：w:del 内的 run 仍在 `paragraph.getRuns()` 返回值中（DebugDel 实证 `run.text()=[被删除内容]`），修订采纳须按 CTR 身份 IdentityHashMap 排除 `del.getRArray()`。
4. **OcrEngine lambda 桩 arity**：`recognize` 为 4 参（baseUrl/apiKeyRef/model/request），单参 lambda 编译报「参数类型不兼容」；测试桩改 `(base, key, model, req) -> ...`。
5. **绑定 kind 白名单**：ModelAdminService 校验 `chat/embedding/rerank`，OCR 绑定被拒；本批加入 `ocr` kind（`purposeKindMatches` 同步加 `OCR → ocr` 映射），错误文案更新为 `chat/embedding/rerank/ocr`。
6. **条件装配类的 @Component 双注册坑**：DocToolProvider / DefaultParserRouter 初稿带 @Component 被组件扫描进所有 Spring 上下文，而其依赖（DocumentApi/DocxParser）仅在 `tioka.ontology.doc.enabled=true` 时装配 → RuntimeApiContractTest 全上下文 9 error。修复：去 @Component，仅经 OntologyDocAutoConfiguration @Bean 注册（KbToolProvider 依赖无条件可用故可用 @Component，非同构）。

## 四、未尽项与偏差登记

1. **docparse 契约放 ontology-api 子包**：M4 拆独立模块时迁移（team-lead 冻结结论）。
2. **space_id 列语义 = workspace_id**：V170 DDL 冻结列名，workspaceId 权威口径以注释标明。
3. **POI 5.2.5 / PDFBox 2.0.32 未进父 pom dependencyManagement**：与 knowledge-biz 版本对齐（knowledge-biz 同版本），登记待父 pom 统一管理。
4. **.doc 解析依赖 LibreOffice headless**：`soffice --headless --convert-to docx`，容器镜像须安装 LibreOffice（环境依赖，DocParser 内 ENV_DEPENDENCY_HINT 提示 doc_convert_failed）。
5. **threeFormats @Disabled**：M3-03 §2 验收「docx/文字型 PDF/扫描 PDF 三格式样本 95% 条款识别」依赖 Q8 外部输入（扫描样本/真实租约），环境就绪后启用。
6. **OcrRoutingIT 去向许可降级口径**：ADR-040 去向许可（DAT-02）未建，「只走空间去向许可允许的模型」降级为「路由按空间绑定解析 + dataLabel/spaceId 随请求传递」，M4 PRS-02 接去向过滤。
7. **V170 四表持久化为 InMemoryDocumentRepository**：ontology-biz 无 JDBC 依赖，persistParsed 单方法单锁段模拟「一事务」；MyBatis 持久化随 SCH 持久化批次接线。
8. **Word 全部行 pageNo=1**：M3 无页码坐标，WORD_PAGE_NO=1 占位。
9. **OCR 图像 token 估算口径**：`len(imagePng)/1024/3`（DefaultModelGateway 预算侧粗估），Bailian 真实计费口径接入时校正。
10. **doc_id 用裸 ULID（26 字符）**：Ids.newId("doc") 为 30 字符前缀格式，V170 列宽 26 冻结 → substring(4)。
11. **DocumentService 事件发布在生产装配层需事务包裹**：测试用 RecordingPublisher 直发；生产经 outbox（EventPublisher MANDATORY 传播）须在事务内调用，装配接线批次确认。
12. **DocumentServiceIT 复测中修复的一处主代码缺陷**：PdfParser LineCollector 未回调 super（实测登记第 1 条），已在 `textPdfParsesWithTextLayerNoOcr` 用例锁定（density=119 → TEXT_PDF、零 OCR 调用断言）。

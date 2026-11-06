# ADR-060 — 文档解析服务与解析输出契约 `tioka.parse/v1`

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-06
- 覆盖工作项：PRS-00、PRS-01、PRS-02、PRS-09、FE-37（新增）；取代 KNW-09
- 来由：[调研 04](../research/04-文档解析与OCR调研.md) 选项 P2、Q1；ADR-029 第 71 条
- 相关：[ADR-018](ADR-018-knowledge-ingest-retrieval.md)、[ADR-024](ADR-024-model-hub-routing.md)、[ADR-026](ADR-026-foundation.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-037](ADR-037-structured-outputs.md)、[ADR-040](ADR-040-dataset-policy-data-label.md)、[ADR-041](ADR-041-config-versioning-observability.md)、[ADR-061](ADR-061-parse-engine-spi-external-ocr.md)、[ADR-062](ADR-062-parse-quality-special-elements-security.md)；Knowledge / RAG 侧为 ADR-055〜059（第 70 条）
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

合同以 Word、PDF、图片三种形式到达（ADR-036），知识库也要入库 PDF、Word、图片与扫描件（ADR-018）。现在有两套解析：代码里的知识库 `DocumentParser`（只输出纯文本），以及 M3-03 设计的文档层 `ParserRouter`（证据单元、坐标、OCR 合并）。两者没有共同的输出契约，知识库切块用不上表格与层级，文档层也没有版面分析、表格结构与印章能力（调研 04 第 7 节 G1〜G13）。

## 决定

1. **新建文档解析服务**（模块 `docparse-api` / `docparse-biz`），负责「文件 → 结构化解析结果」。文档层（ADR-036）与知识库（ADR-018 及 ADR-055〜059）都只消费它的输出，不再各自解析。
2. **解析采用流水线**，VLM 只做区域识别器，不做端到端整页生成（调研 04 第 2 节，选项 S1）。
3. **输出契约为 `tioka.parse/v1`**（第 3 节），是解析服务与所有消费方之间唯一的接口。Markdown 与纯文本是派生视图，不能作为证据来源。
4. **引擎可替换**：每个阶段用哪个引擎由「解析方案」决定（第 4 节），引擎按 [ADR-061](ADR-061-parse-engine-spi-external-ocr.md) 的 SPI 接入。
5. **切块不在本 ADR**：切块、向量化、索引由 Knowledge 负责（ADR-055〜059）；本服务只保证契约里有切块所需的层级、表格与阅读顺序。

## 1. 模块与边界

| 项 | 定案 |
| --- | --- |
| 模块 | `tioka-module-docparse`（api / biz）；依赖 `model-api`（模型引擎）、`connector-api`（SDK 公共件）、`infra-api`（对象存储、插件宿主）；被 `ontology-biz`、`knowledge-biz`、`chat-biz`（附件）依赖 |
| 对外接口 | `DocParseApi`：提交解析（文件引用、解析方案键、空间、数据标签，异步返回 `parseId`）、查询状态、读取结果（整份或按页范围）、读取页图 |
| 事件 | `tioka.docparse.document_parsed.v1`（parseId、文件哈希、方案版本、状态、质量摘要）；文档层与知识库订阅它推进各自流程 |
| 存储 | 解析结果 JSON 与页图放对象存储；登记表 `dps_parse` 只存索引字段（第 5 节） |
| 运行 | 解析任务经 RocketMQ 派发，按阶段推进，与 KNW-05 的入库任务同一模式；一个文档的页可并行识别 |
| 不做 | 切块、向量化、业务抽取（属于 Knowledge 与 @model.build）；文档版本对比（M4 之后另议） |

M3 时模块尚未拆出：契约类型先放 `docparse-api`，实现仍在 `ontology-biz` 的 `ParserRouter`（M3-03），M4 迁入 `docparse-biz`（PRS-01）。

## 2. 流水线

| 阶段 | 做法 | 输出 |
| --- | --- | --- |
| 1 预检 | 按文件头字节识别真实类型（不信扩展名与 MIME）；加密文档识别；大小、页数、解压比、嵌套深度限额（ADR-062 第 4 节）；计算 SHA-256 并查缓存 | 通过 / 拒绝原因 |
| 2 规范化 | doc、wps、rtf → docx（LibreOffice 无界面模式）；多页 TIFF、多张图片 → 每图一页并按上传顺序排序；HEIC → PNG；xlsx 每个工作表转为一个表格（POI，进程内，取单元格显示值）；OFD 在 M4 之后（PRS-11） | 规范化文件 |
| 3 页分类 | 每页判为 `NATIVE`（Word）、`TEXT_PDF`（文字层可信）、`SCANNED`（无文字层或文字层不可信）、`MIXED`（文字层只覆盖部分区域）、`IMAGE`；判据为字符密度、乱码率、文字层覆盖面积与图像面积之比；`TEXT_PDF` 页按 ADR-062 第 3 节抽检文字层与图像是否一致 | 页类型 |
| 4 页图 | 所有 PDF 与图片页渲染为 200 DPI 的 PNG，存对象存储；方向校正与纠偏在此完成，并记录旋转角 | 页图、页尺寸 |
| 5 版面 | 对页图做版面检测：区域类别、坐标、阅读顺序；Word 页跳过（结构取自文档本身） | 区域列表 |
| 6 区域识别 | 按区域类别分派：文字区（`TEXT_PDF` 取文字层，其余 OCR）、表格区（表格结构识别）、印章区（印章识别）、手写判定、勾选框判定 | 区域内容 |
| 7 融合 | 文字层与 OCR 合并（沿用 M3-03 第 3 节的 IoU 规则）；第二意见引擎复核低置信区域；数字与日期类区域两个来源不一致时打 `SOURCE_CONFLICT` 标记 | 融合后的块 |
| 8 组装 | 标题层级、列表编号、条款编号；跨页段落与跨页续表合并；页眉、页脚、页码归入 `FURNITURE`；空白填写位识别（沿用 M3-03 的五种写法） | 块树 |
| 9 质量评估 | 页级与文档级质量分；标记汇总；必要时状态为 `PARTIAL` | 契约 v1 输出 |

### 2.1 Word 路线

Word 不走版面与 OCR，结构直接取自文档，规则如下：

| 项 | 规则 |
| --- | --- |
| 自动编号 | 按 numbering 定义的级别、格式与计数器计算出渲染文字（如「第二十二条」「4.1.1」「（三）」），写入块的 `listLabel`，并拼在 `text` 前面；**这是条款树能否建出来的前提**（调研 04 G1） |
| 表格 | 按单元格输出，记录行列、合并单元格（横向与纵向）与嵌套表格 |
| 修订 | 输出「接受全部修订」后的正文；被删除的内容不进正文；块上标 `REVISED`，文档级标记「含未接受修订」，供人工确认时提示 |
| 批注 | 不进正文；单独作为 `COMMENT` 块（`role = ANNOTATION`），供审查参考 |
| 页眉页脚 | `role = FURNITURE` |
| 文本框、形状内文字 | 作为正文块，按锚点位置排在所在段落之后 |
| 隐藏文字 | 不进正文，打 `HIDDEN_TEXT` 标记（ADR-062 第 3 节） |
| 页码与坐标 | M3 中 Word 块没有页码与坐标；M4 起把 docx 用 LibreOffice 渲染为 PDF，按文字匹配回填页码与 bbox，供界面高亮，匹配不上的块 bbox 为空 |

### 2.2 PDF 与图片路线

- `TEXT_PDF` 页也做版面检测（阶段 5），只是文字取自文字层。这样多栏、表格、页眉页脚的处理与扫描页一致（调研 04 G3）。
- `SCANNED`、`IMAGE` 页全部走区域识别；`MIXED` 页文字层覆盖不到的区域走 OCR。
- M3 尚无版面引擎：`SCANNED` 页按 M3-03 的「行序列 + 规则切分」处理，输出同样遵守契约 v1 的核心子集（第 3.6 节）。

## 3. 输出契约 `tioka.parse/v1`

### 3.1 文档

| 字段 | 说明 |
| --- | --- |
| `contract` | 固定为 `tioka.parse/v1` |
| `parseId` | ULID |
| `source` | 文件哈希、原文件名、检测到的类型、页数、原文件对象键 |
| `profile` | 解析方案键与版本（第 4 节） |
| `engines` | 本次实际使用的引擎列表：引擎实例、供应商声明的版本、用于哪些页与阶段 |
| `status` | `READY`（全部页成功）/ `PARTIAL`（部分页失败或降级，失败页列在 `warnings`）/ `FAILED` |
| `language` | 主要语言 |
| `quality` | 文档质量分（0〜1）、低置信块数、手写块数、标记统计 |
| `flags` | 文档级标记：`HAS_REVISIONS`、`HAS_HANDWRITING`、`HAS_SEAL`、`TEXT_LAYER_MISMATCH`、`HIDDEN_TEXT`、`DEGRADED_ENGINE` |
| `pages` | 页数组（3.2） |
| `blocks` | 块数组，按阅读顺序排列（3.3） |
| `tables` | 表格数组（3.4） |
| `warnings` | 失败页、降级原因、被跳过的元素 |

### 3.2 页

| 字段 | 说明 |
| --- | --- |
| `pageNo` | 从 1 开始 |
| `kind` | `NATIVE` / `TEXT_PDF` / `SCANNED` / `MIXED` / `IMAGE` |
| `width`、`height` | 页图像素尺寸（200 DPI）；Word 在 M3 为空 |
| `rotation` | 方向校正时旋转的角度 |
| `imageKey` | 页图对象键 |
| `quality` | 页质量分；文字层可信度 |

### 3.3 块

| 字段 | 说明 |
| --- | --- |
| `blockId` | `{fileHash 前 16 位}:{pageNo}:{seq}`；同一文件、同一方案版本解析结果的 `blockId` 稳定 |
| `seq` | 文档内全局阅读顺序 |
| `type` | `HEADING`、`PARAGRAPH`、`LIST_ITEM`、`TABLE`、`TABLE_CELL`、`SEAL`、`SIGNATURE`、`CHECKBOX`、`BLANK_FIELD`、`PICTURE`、`CAPTION`、`FORMULA`、`COMMENT`、`PAGE_HEADER`、`PAGE_FOOTER`、`PAGE_NUMBER` |
| `role` | `BODY`（正文）/ `FURNITURE`（页眉页脚页码）/ `ANNOTATION`（批注） |
| `parentId` | 上级块：标题 → 段落、表格 → 单元格、列表嵌套 |
| `level` | 标题或列表层级 |
| `listLabel` | 渲染后的编号文字，如「第二十二条」 |
| `text` | 块文字（含编号）；印章为印章文字；签名、图片为空 |
| `regions` | 坐标列表：每项为页码 + 页内 0〜1 归一化的 `[x0, y0, x1, y1]`，可选多边形；跨页的段落与续表有多项 |
| `source` | `NATIVE`（Word 原文）/ `TEXT_LAYER` / `OCR` / `VLM` / `MERGED` |
| `engine` | 产生该块文字的引擎实例 |
| `confidence` | 0〜1；`NATIVE` 与可信 `TEXT_LAYER` 为 1 |
| `attrs` | 类型相关属性：`handwritten`、`checked`（勾选框）、印章的 `shape` / `color` / `occludes`（被遮挡块的 ID）、`revision`、`tableId` 与行列 |
| `flags` | `LOW_CONFIDENCE`、`HANDWRITTEN`、`OCCLUDED_BY_SEAL`、`SOURCE_CONFLICT`、`TEXT_LAYER_MISMATCH`、`HIDDEN_TEXT`、`REVISED` |

### 3.4 表格

| 字段 | 说明 |
| --- | --- |
| `tableId`、`blockId` | 表格对应的 `TABLE` 块 |
| `rows`、`cols` | 行列数 |
| `cells` | 单元格：行、列、行跨度、列跨度、是否表头、`blockId`（对应 `TABLE_CELL` 块，文字与坐标在块上） |
| `continuedFrom` | 跨页续表时指向上一页表格；组装阶段合并后，合并表保留各页 `regions` |
| `html` | 派生视图，方便展示与检索；不是证据来源 |

### 3.5 演进规则

- v1 内**只增不改**：只能增加可选字段与新的 `type` / `flags` 取值；消费方遇到未知的 `type` 按 `PARAGRAPH` 处理，遇到未知字段忽略。
- 删除或改变字段含义须发 v2，并有至少一个里程碑的双写期。
- 契约变更走 ADR-030，并通知 Knowledge 与文档层的负责人。

### 3.6 M3 核心子集

M3（PRS-00）只要求：`pages`（kind、尺寸、页图）、`blocks`（`HEADING`、`PARAGRAPH`、`LIST_ITEM`、`TABLE_CELL`、`BLANK_FIELD`、`SEAL`、`PAGE_HEADER`、`PAGE_FOOTER`；`listLabel`、`regions`、`source`、`confidence`、`flags` 中的 `LOW_CONFIDENCE` 与 `REVISED`）、`tables`。其他类型与标记在 M4 补齐。文档层的 `doc_unit` 按此子集映射，M4 不改表结构。

## 4. 解析方案（Profile）

解析方案决定每类页、每种区域用哪个引擎，是 ADR-041 的版本化配置（发布后不可改，改动生成新版本）。

| 字段 | 说明 |
| --- | --- |
| 键与版本 | 如 `standard-cloud@3` |
| 适用 | 空间、知识库或文档层用途（合同、参考资料）可各自指定方案；未指定的用系统默认 |
| 路由表 | 按「页类型 × 阶段」指定主引擎、第二意见引擎（可选）与降级链 |
| 阈值 | 文字层可信判据、低置信阈值（缺省 0.8）、第二意见触发阈值 |
| 去向 | 方案里的每个引擎都要满足文档数据标签的去向许可（ADR-040）；不满足的引擎在路由时跳过，整条链都不满足时解析失败并说明原因，不静默降级到不合规的引擎 |

**默认方案**（选项 Q1）：

| 方案 | 适用 | 版面 | 文字识别 | 表格 | 印章 | 第二意见 |
| --- | --- | --- | --- | --- | --- | --- |
| `standard-private` | 私有化默认；`SECRET` 文档 | PaddleOCR 服务 | PaddleOCR 服务（GPU 可用时用 PaddleOCR-VL，否则 PP-OCR） | PaddleOCR 服务 | PaddleOCR 服务 | 无 |
| `standard-cloud` | 允许 `CONFIDENTIAL` 出网的部署（当前租赁合同部署） | PaddleOCR 服务 | PaddleOCR 服务 | PaddleOCR 服务 | PaddleOCR 服务 | 百炼 `qwen-vl-ocr`，只用于低置信、手写与数字日期区域 |
| `m3-baseline` | M3 过渡 | 无（规则） | 百炼 `qwen-vl-ocr` | 规则 | 无 | 无 |

外购产品（如阿里云文档解析）作为可选引擎写进方案，可整份替换版面与识别，也可只做第二意见（ADR-061）。

## 5. 缓存、存储与保留

| 项 | 定案 |
| --- | --- |
| 缓存键 | 文件哈希 + 方案版本 + 空间；命中直接返回已有 `parseId` |
| 登记表 `dps_parse` | parseId、缓存键三项、状态、质量摘要、标记、结果对象键、页数、引擎列表、数据标签、创建时间（没有 `tenant_id`，第 60 条） |
| 引擎任务表 `dps_engine_job` | 见 ADR-061 第 3 节 |
| 结果与页图 | 对象存储；结果 JSON 按页分片存放，便于按页读取 |
| 外部引擎原始结果 | 原样存对象存储，与解析结果同保留期，供排查与重映射 |
| 保留 | 与原文件一致；原文件按业务删除时，解析结果、页图、原始结果一并删除 |
| 重解析 | 方案升级后不自动重解析存量；文档层与知识库按需调用「以新方案重解析」，旧结果在新结果 READY 后删除 |

## 6. 消费方映射

| 消费方 | 映射 |
| --- | --- |
| 文档层（ADR-036） | `doc_page` ← 页；`doc_unit` ← 块（`PARAGRAPH`、`LIST_ITEM` → PARA，`HEADING` → HEADING，`TABLE_CELL` → CELL，`SEAL` 与 `SIGNATURE` → SIGN，`BLANK_FIELD` → BLANK_FIELD；M4 增加 SEAL、CHECKBOX 两种 kind）；`unit_id` 沿用 `{docId}:{pageNo}:{seq}`，并记录来源 `blockId`；`ocr_conf` ← 块 `confidence`；条款树由 `listLabel` 与标题层级建立 |
| 知识库（ADR-018，ADR-055〜059） | 只取 `role = BODY` 的块；按 `parentId` 与 `level` 得到标题路径；表格整表或按行切由 Knowledge 决定；块的 `regions` 作为引用定位信息保存在分块元数据中 |
| Chat 附件（ADR-022） | 使用派生的 Markdown 视图；附件超过上下文预算时按 ADR-048 卸载 |
| 解析结果查看器（FE-37） | 页图 + 块框叠加；低置信、手写、冲突、遮挡按颜色高亮；点击块显示来源引擎与置信度；ONT-08 确认页复用该组件 |

## 7. 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| PRS-00 | 契约 v1 核心子集类型；Word 自动编号渲染、表格（含合并单元格）、修订采纳与批注分离；文档层按核心子集输出 | M3 | P0 | 后端 2.5 | DOC-01 | 自动编号的 Word 合同条款树与人工标注一致；修订稿删除内容不进正文 |
| PRS-01 | `docparse` 模块与九阶段流水线、缓存、异步任务、页图、`document_parsed` 事件；迁入 DOC-01 / DOC-02 实现；Word 渲染回填页码与 bbox | M4 | P0 | 后端 5 | PRS-00、KNW-05 | 同一文件两次提交只解析一次；文字型 PDF 的多栏页阅读顺序正确 |
| PRS-02 | 解析方案配置与版本化、路由表、降级链、去向过滤、第二意见 | M4 | P0 | 后端 3 | PRS-01、ADR-041、DAT-02 | `SECRET` 文档在只有云引擎的方案下失败并说明原因 |
| PRS-09 | 知识库入库改用解析服务（取代 KNW-09），与 Knowledge 切块对接 | M4 | P1 | 后端 2 | PRS-01、ADR-055〜059 的切块工作项 | 扫描件 PDF 进知识库后表格可被检索到单元格内容 |
| FE-37 | 解析结果查看器组件（页图、块框、高亮、来源）；ONT-08 复用 | M4 | P1 | 前端 4 | PRS-01 | 低置信与手写块高亮可见，点击显示来源引擎 |

PRS-03〜08 见 ADR-061、ADR-062；PRS-10〜12、FE-38 在 M4 之后。

package com.tioka.knowledge.application.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.api.docparse.ParsedUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNW-03 验收（M3-06 §3.2 / ADR-055 §2）：结构感知切块快照测试。
 * 快照以 ParseResult JSON 样本资源承载（Markdown 结构 / PDF 带表格 / FAQ / 小文档 / 超长小节）；
 * 端到端五格式解析由 DOC 域解析测试既有覆盖承载（登记口径）。
 * 快照更新：{@code -Dchunker.snapshots=update}（评审 diff 后提交，禁止随手覆盖）。
 */
class StructuredChunkerTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path SAMPLE_DIR = Path.of("src/test/resources/chunking");
    private static final Path EXPECTED_DIR = Path.of("src/test/resources/chunking/expected");

    private ParseResult sample(String name) throws Exception {
        return JSON.readValue(Files.readString(SAMPLE_DIR.resolve(name + ".json")), ParseResult.class);
    }

    private List<StructuredChunker.ChunkPiece> snapshotted(String name, String docTitle,
                                                           StructuredChunker.ChunkPolicy policy,
                                                           boolean contextEnhancement) throws Exception {
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(policy, contextEnhancement).chunk(sample(name), docTitle);
        String actual = JSON.writeValueAsString(pieces);
        Path expected = EXPECTED_DIR.resolve(name + ".json");
        if (Boolean.getBoolean("chunker.snapshots.update")) {
            Files.createDirectories(expected.getParent());
            Files.writeString(expected, actual + "\n");
        }
        assertTrue(Files.isRegularFile(expected), "缺少快照: " + expected);
        String expectedJson = Files.readString(expected).strip();
        assertEquals(expectedJson, actual.strip(), "快照不一致（更新：-Dchunker.snapshots=update）");
        return pieces;
    }

    @Test
    void markdownStructureSnapshot() throws Exception {
        List<StructuredChunker.ChunkPiece> pieces = snapshotted(
                "01_markdown_structure", "平台架构白皮书", StructuredChunker.ChunkPolicy.auto(), true);
        // 结构断言：H1→H2 两级路径；FURNITURE/ANNOTATION 不进切块；前缀 = 文档标题 > 各级标题
        StructuredChunker.ChunkPiece dataParent = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType()))
                .filter(p -> p.text().contains("数据面负责文档解析"))
                .findFirst().orElseThrow();
        assertEquals("平台架构白皮书 > 架构总览 > 数据面", dataParent.contextPrefix());
        assertTrue(pieces.stream().noneMatch(p -> p.text().contains("第 1 页")));
        assertTrue(pieces.stream().noneMatch(p -> p.text().contains("批注")));
        // 子块全部携带标题路径前缀（嵌入/全文索引口径）
        assertEquals(0, pieces.stream()
                .filter(p -> StructuredChunker.TYPE_CHILD.equals(p.chunkType()))
                .filter(c -> c.contextPrefix() == null || c.contextPrefix().isBlank()).count());
    }

    @Test
    void pdfTableCellsSnapshot() throws Exception {
        List<StructuredChunker.ChunkPiece> pieces = snapshotted(
                "02_pdf_table_cells", "项目报价单", StructuredChunker.ChunkPolicy.auto(), true);
        // 表格整表一个子块（Markdown 形式），表头行在前；上下文前缀带最近段落说明
        StructuredChunker.ChunkPiece tableChild = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_CHILD.equals(p.chunkType()))
                .filter(p -> p.text().startsWith("项目 | 金额"))
                .findFirst().orElseThrow();
        assertEquals("项目 | 金额\n实施 | 120 万\n运维 | 40 万", tableChild.text());
        assertTrue(tableChild.contextPrefix().startsWith("项目报价单 > 报价明细"));
        assertTrue(tableChild.contextPrefix().endsWith("（表说明：下表列出各项目报价。）"));
        // 页码与来源 blockId：表格跨页 2〜3
        assertEquals(2, tableChild.pageStart());
        assertEquals(3, tableChild.pageEnd());
        assertEquals(1, tableChild.blockIds().size());
        assertTrue(tableChild.blockIds().get(0).startsWith("tbl:7:doc_sample_tbl:2:3"),
                "tableRef 聚合表 blockId = tbl:{tableId}:{首格 blockId}: " + tableChild.blockIds());
    }

    @Test
    void faqSnapshot() throws Exception {
        List<StructuredChunker.ChunkPiece> pieces = snapshotted(
                "03_faq", "知识库 FAQ", StructuredChunker.ChunkPolicy.auto(), true);
        // FAQ 型：每组问答一个父块（3 组问答 → 3 父块）
        long parents = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType())).count();
        assertEquals(3, parents);
        assertTrue(pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType()))
                .anyMatch(p -> p.text().startsWith("如何创建知识库？")));
        // 问句包含在子块中（嵌入可召回问句）
        assertTrue(pieces.stream()
                .filter(p -> StructuredChunker.TYPE_CHILD.equals(p.chunkType()))
                .anyMatch(c -> c.text().startsWith("如何调整切块策略？")));
    }

    @Test
    void smallDocSnapshot() throws Exception {
        List<StructuredChunker.ChunkPiece> pieces = snapshotted(
                "04_small_doc", "小文档", StructuredChunker.ChunkPolicy.auto(), true);
        // 小文档：整篇一个父块
        assertEquals(1, pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType())).count());
        assertEquals("小文档",
                pieces.get(0).contextPrefix());
    }

    @Test
    void longSectionSnapshot() throws Exception {
        List<StructuredChunker.ChunkPiece> pieces = snapshotted(
                "05_long_section", "实施方案手册", StructuredChunker.ChunkPolicy.auto(), true);
        // 超长小节：按段落边界拆为多个父块，标题路径相同；起止页覆盖 9〜15
        List<StructuredChunker.ChunkPiece> parents = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType())).toList();
        assertTrue(parents.size() >= 2, "超长小节应拆为多个父块: " + parents.size());
        parents.forEach(p -> assertEquals("实施方案手册 > 实施方案", p.contextPrefix()));
        assertEquals(9, pieces.stream().mapToInt(StructuredChunker.ChunkPiece::pageStart).min().orElseThrow());
        assertEquals(15, pieces.stream().mapToInt(StructuredChunker.ChunkPiece::pageEnd).max().orElseThrow());
        // 父块不超上限（段落边界切分，单段超限例外）
        assertTrue(parents.stream().allMatch(p -> p.text().length() <= 2000 || p.text().length() <= 1200),
                "父块按段落边界拆分");
    }

    // ------------------------------------------------------------------
    // 规则单元断言（不进快照）
    // ------------------------------------------------------------------

    @Test
    void oversizeTableSplitsByRowGroupsRepeatingHeader() {
        StringJoiner rows = new StringJoiner("\n");
        rows.add("指标 | 数值 | 说明");
        for (int i = 0; i < 60; i++) {
            rows.add(String.format("指标%02d 的完整名称 | %d.0 | 补充说明文字占位用固定长度四十个汉字的描述以撑起行宽字数。", i, i));
        }
        String tableText = rows.toString();
        assertTrue(tableText.length() > 1500, "样本表应超过整表阈值: " + tableText.length());
        ParseResult result = parseOf(List.of(
                new ParsedUnit("t:1", 1, ParseContract.T_TABLE, ParseContract.R_BODY, null, 0, null,
                        tableText, List.of(new ParsedRegion(2, 0, 0, 0, 0)), null, null, List.of(), null, null)));
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(StructuredChunker.ChunkPolicy.auto(), false).chunk(result, "大表文档");
        List<StructuredChunker.ChunkPiece> tableChildren = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_CHILD.equals(p.chunkType())).toList();
        assertTrue(tableChildren.size() >= 2, "大表应按行组切分");
        tableChildren.forEach(c -> {
            assertTrue(c.text().startsWith("指标 | 数值 | 说明\n"), "每块重复表头行");
            assertTrue(c.text().length() <= StructuredChunker.TABLE_SINGLE_BLOCK_MAX_CHARS + 80);
        });
    }

    @Test
    void contextEnhancementOffStripsPrefix() {
        ParseResult result = parseOf(List.of(
                new ParsedUnit("t:1", 1, ParseContract.T_HEADING, ParseContract.R_BODY, null, 1, null,
                        "标题", List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null),
                new ParsedUnit("t:2", 2, ParseContract.T_PARAGRAPH, ParseContract.R_BODY, null, 0, null,
                        "正文第一句。", List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null)));
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(StructuredChunker.ChunkPolicy.auto(), false).chunk(result, "某文档");
        pieces.forEach(p -> assertEquals("", p.contextPrefix(), "上下文增强关闭 → 前缀为空"));
    }

    @Test
    void policyOverridesSizes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("第").append(i).append("句，内容用于撑起长度以测试策略覆盖的子块切分。");
        }
        ParseResult result = parseOf(List.of(
                new ParsedUnit("t:1", 1, ParseContract.T_PARAGRAPH, ParseContract.R_BODY, null, 0, null,
                        sb.toString(), List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null)));
        StructuredChunker.ChunkPolicy tight = new StructuredChunker.ChunkPolicy(
                "auto", 100, 50, 10, null);
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(tight, false).chunk(result, "策略文档");
        List<StructuredChunker.ChunkPiece> parents = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_PARENT.equals(p.chunkType())).toList();
        assertTrue(parents.size() >= 2, "parentMaxChars=100 应拆分父块");
        List<StructuredChunker.ChunkPiece> children = pieces.stream()
                .filter(p -> StructuredChunker.TYPE_CHILD.equals(p.chunkType())).toList();
        assertTrue(children.size() >= 2, "childTargetChars=50 应多子块");
        assertTrue(children.stream().allMatch(c -> c.text().length() <= 130), "子块不超硬上限");
    }

    @Test
    void imageDroppedCaptionKept() {
        ParseResult result = parseOf(List.of(
                new ParsedUnit("t:1", 1, "IMAGE", ParseContract.R_BODY, null, 0, null,
                        "[图片]", List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null),
                new ParsedUnit("t:2", 2, "CAPTION", ParseContract.R_BODY, null, 0, null,
                        "图 1：架构示意图。", List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null)));
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(StructuredChunker.ChunkPolicy.auto(), true).chunk(result, "图文文档");
        assertTrue(pieces.stream().noneMatch(p -> p.text().equals("[图片]")), "图块丢弃");
        assertTrue(pieces.stream().anyMatch(p -> p.text().contains("图 1：架构示意图")), "只保留 CAPTION");
    }

    private static ParseResult parseOf(List<ParsedUnit> units) {
        return new ParseResult(ParseContract.CONTRACT, "unit-sample", "hash", "1.0.0",
                ParseContract.ST_READY, 1, null, units, List.of(), List.of());
    }
}

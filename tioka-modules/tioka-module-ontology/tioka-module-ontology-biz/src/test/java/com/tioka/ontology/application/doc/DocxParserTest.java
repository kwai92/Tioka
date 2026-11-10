package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocxParser（DOC-01 / PRS-00）：自动编号渲染、修订采纳、批注分离、表格按单元格（含合并单元格）。
 */
class DocxParserTest {

    private final DocxParser parser = new DocxParser();

    @Test
    void autoNumberingRenderedDecimalAndChinese() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            // decimal "%1." → 1. / 2.
            CTLvl dec = CTLvl.Factory.newInstance();
            dec.setIlvl(BigInteger.ZERO);
            dec.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            dec.addNewLvlText().setVal("%1.");
            dec.addNewStart().setVal(BigInteger.ONE);
            CTAbstractNum absDec = CTAbstractNum.Factory.newInstance();
            absDec.setAbstractNumId(BigInteger.ZERO);
            absDec.addNewLvl().set(dec);
            doc.createNumbering().addAbstractNum(
                    new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(absDec));
            // POI 5.2.5：两参 addNum(numId, abstractNumId) 注册后 getNum 查不到（实测登记），
            // 必须用单参 addNum(abstractNumId) 返回的 numId
            BigInteger numId = doc.getNumbering().addNum(BigInteger.ZERO);

            XWPFParagraph p1 = doc.createParagraph();
            p1.setNumID(numId);
            p1.createRun().setText("租赁标的");
            XWPFParagraph p2 = doc.createParagraph();
            p2.setNumID(numId);
            p2.createRun().setText("租金标准");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }
        List<ParsedLine> lines = parser.parse(bytes).lines();
        assertEquals("1.租赁标的", lines.get(0).text());
        assertEquals("2.租金标准", lines.get(1).text());
    }

    @Test
    void chineseCountingNumberingRendersClauseLabel() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            CTLvl cn = CTLvl.Factory.newInstance();
            cn.setIlvl(BigInteger.ZERO);
            cn.addNewNumFmt().setVal(STNumberFormat.CHINESE_COUNTING_THOUSAND);
            cn.addNewLvlText().setVal("第%1条");
            cn.addNewStart().setVal(BigInteger.ONE);
            CTAbstractNum abs = CTAbstractNum.Factory.newInstance();
            abs.setAbstractNumId(BigInteger.ZERO);
            abs.addNewLvl().set(cn);
            doc.createNumbering().addAbstractNum(
                    new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(abs));
            BigInteger numId = doc.getNumbering().addNum(BigInteger.ZERO);

            XWPFParagraph p = doc.createParagraph();
            p.setNumID(numId);
            p.createRun().setText("租金与押金");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }
        List<ParsedLine> lines = parser.parse(bytes).lines();
        assertEquals("第一条租金与押金", lines.get(0).text());
        // 命中条款标题正则（自动编号是条款树的前提，调研 05 G1）
        assertTrue(UnitSegmenter.isHeadingLike(lines.get(0).text(), lines.get(0)));
    }

    @Test
    void deletedContentExcludedAndFlagged() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("保留正文。");
            // w:del / w:delText：被删除内容
            p.getCTP().addNewDel().addNewR().addNewDelText().setStringValue("被删除内容");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }
        DocxParser.DocxOutput out = parser.parse(bytes);
        assertFalse(out.lines().isEmpty());
        String text = out.lines().get(0).text();
        assertFalse(text.contains("被删除内容"), "修订采纳：被删除内容不进正文");
        assertTrue(out.flags().contains(DocxParser.FLAG_HAS_REVISIONS), "文档级 HAS_REVISIONS");
    }

    @Test
    void commentsSeparatedAsAnnotationLines() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("正文。");
            org.apache.poi.xwpf.usermodel.XWPFComments comments = doc.createComments();
            org.apache.poi.xwpf.usermodel.XWPFComment c = comments.createComment(BigInteger.ONE);
            c.setAuthor("王五");
            c.createParagraph().createRun().setText("此处需复核");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }
        DocxParser.DocxOutput out = parser.parse(bytes);
        assertEquals(1, out.lines().size(), "批注不进正文");
        assertEquals(1, out.comments().size());
        assertEquals("COMMENT", out.comments().get(0).kind());
        assertTrue(out.comments().get(0).text().contains("此处需复核"));
        assertTrue(out.comments().get(0).text().contains("王五"));
    }

    @Test
    void tableCellsOneLineEachWithVerticalMergeUpperLeft() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("费用项");
            table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("租金");
            table.getRow(1).getCell(1).setText("10000元/月");
            table.getRow(2).getCell(0).setText("押金");
            table.getRow(2).getCell(1).setText("20000元");
            // 纵向合并第 2 列第 2〜3 行：第 3 行 vMerge=continue
            var tc = table.getRow(2).getCell(1).getCTTc();
            tc.addNewTcPr().addNewVMerge().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }
        List<ParsedLine> lines = parser.parse(bytes).lines();
        // continue 单元格跳过：合并单元格取左上角
        assertEquals(5, lines.size());
        for (ParsedLine l : lines) {
            assertEquals("CELL", l.kind());
        }
        assertTrue(lines.stream().noneMatch(l -> l.text().isEmpty()));
    }
}

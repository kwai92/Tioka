package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedTableRef;
import com.tioka.ontology.api.docparse.ParsedUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UnitSegmenter 表驱动（DOC-01 切分规则，M3-03 §2）：
 * 跨页段落合并、表格单元格、空白填写位 5 种写法、签章区、section 切换。
 */
class UnitSegmenterTest {

    private static ParsedLine para(int pageNo, String text) {
        return new ParsedLine(pageNo, text, List.of(), "PARA", null,
                ParseContract.S_TEXT_LAYER, null);
    }

    private static ParsedLine cell(int tableId, int row, int col, String text) {
        return new ParsedLine(1, text, List.of(), "CELL",
                new ParsedTableRef(tableId, row, col), ParseContract.S_NATIVE, null);
    }

    @Test
    void headingsOfThreePatterns() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                para(1, "第一条 租赁标的"),
                para(1, "4.1.1 租金构成"),
                para(1, "（三） 付款方式"),
                para(1, "正文段落。")), "doc1");
        assertEquals(4, units.size());
        assertEquals(ParseContract.T_HEADING, units.get(0).type());
        assertEquals(ParseContract.T_HEADING, units.get(1).type());
        assertEquals(ParseContract.T_HEADING, units.get(2).type());
        assertEquals(ParseContract.T_PARAGRAPH, units.get(3).type());
        // seq 全局顺序
        assertEquals(1, units.get(0).seq());
        assertEquals(4, units.get(3).seq());
    }

    @Test
    void crossPageParagraphMergedWhenNoPeriodAndNoNumber() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                para(1, "本合同项下租金由乙方按季度支付至甲方指定账户，逾期"),
                para(2, "按日计收滞纳金。")), "doc1");
        assertEquals(1, units.size(), "跨页段落合并为一个单元");
        assertTrue(units.get(0).text().endsWith("滞纳金。"));
    }

    @Test
    void crossPageParagraphNotMergedWhenPeriodOrNumber() {
        // 上页末行以句号结尾 → 不合并
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                para(1, "第一段结束。"),
                para(2, "第二段开始。")), "doc1");
        assertEquals(2, units.size());
        // 下一页开头有编号 → 不合并
        List<ParsedUnit> units2 = UnitSegmenter.segment(List.of(
                para(1, "租金支付方式见下"),
                para(2, "第二条 逾期责任")), "doc2");
        assertEquals(2, units2.size());
    }

    @Test
    void tableCellsOneUnitEach() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                cell(1, 0, 0, "费用项"),
                cell(1, 0, 1, "金额"),
                cell(1, 1, 0, "租金"),
                cell(1, 1, 1, "10000 元/月")), "doc1");
        assertEquals(4, units.size(), "表格按单元格切，不整表拼段");
        for (ParsedUnit u : units) {
            assertEquals("CELL", u.type());
            assertTrue(u.tableRef() != null);
        }
        assertEquals(1, units.get(2).tableRef().row(), "第二行");
        assertEquals(1, units.get(3).tableRef().row(), "第二行");
        assertEquals(0, units.get(2).tableRef().col(), "第一列");
    }

    /** 空白填写位 5 种写法各有用例（M3-03 §2）。 */
    @Test
    void blankFieldsFiveWritings() {
        List<String> blanks = List.of(
                "承租人：【 】",
                "月租金：______",
                "承租人：（ ）",
                "／",
                "押金：＿＿＿元");
        List<ParsedUnit> units = UnitSegmenter.segment(
                blanks.stream().map(t -> para(1, t)).toList(), "doc1");
        for (ParsedUnit u : units) {
            assertEquals(ParseContract.T_BLANK_FIELD, u.type(), () -> "应为空白位: " + u.text());
        }
    }

    @Test
    void signZoneAfterStampKeywords() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                para(1, "本合同一式两份。"),
                para(1, "甲方（盖章）："),
                para(1, "2026年1月1日"),
                para(1, "李四")), "doc1");
        assertEquals("SIGN", units.get(1).type());
        assertEquals("SIGN", units.get(2).type());
        assertEquals("SIGN", units.get(3).type());
    }

    @Test
    void sectionSwitchOnKeywords() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                para(1, "定义"),
                para(1, "本合同中……"),
                para(1, "专用条款"),
                para(1, "租金标准……"),
                para(1, "附件"),
                para(1, "设备清单")), "doc1");
        assertEquals("DEF", units.get(1).section());
        assertEquals("SPECIAL", units.get(3).section());
        assertEquals("ANNEX", units.get(5).section());
    }

    @Test
    void commentLinesAreSeparateAnnotationUnits() {
        List<ParsedUnit> units = UnitSegmenter.segment(List.of(
                new ParsedLine(1, "[批注 王五] 此处需复核", List.of(), "COMMENT", null,
                        ParseContract.S_NATIVE, null),
                para(1, "正文。")), "doc1");
        assertEquals(ParseContract.T_COMMENT, units.get(0).type());
        assertEquals(ParseContract.R_ANNOTATION, units.get(0).role());
        assertEquals(ParseContract.T_PARAGRAPH, units.get(1).type());
    }
}

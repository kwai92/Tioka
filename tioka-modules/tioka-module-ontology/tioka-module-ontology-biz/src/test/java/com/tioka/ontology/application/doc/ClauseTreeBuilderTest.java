package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ClauseTreeBuilder（DOC-01）：「第二十二条」「4.1.1」「（三）」三种编号混排时树结构正确。
 */
class ClauseTreeBuilderTest {

    private static ParsedUnit heading(int seq, String text) {
        return new ParsedUnit("doc1:1:" + seq, seq, ParseContract.T_HEADING,
                ParseContract.R_BODY, null, 0, null, text, List.of(),
                ParseContract.S_NATIVE, null, List.of(), null, null);
    }

    private static ParsedUnit para(int seq, String text) {
        return new ParsedUnit("doc1:1:" + seq, seq, ParseContract.T_PARAGRAPH,
                ParseContract.R_BODY, null, 0, null, text, List.of(),
                ParseContract.S_NATIVE, null, List.of(), null, null);
    }

    private static ParsedUnit blank(int seq, String text) {
        return new ParsedUnit("doc1:1:" + seq, seq, ParseContract.T_BLANK_FIELD,
                ParseContract.R_BODY, null, 0, null, text, List.of(),
                ParseContract.S_NATIVE, null, List.of(), null, null);
    }

    @Test
    void mixedNumberingBuildsCorrectTree() {
        List<ParsedUnit> units = List.of(
                heading(1, "第二十二条 租金支付"),
                para(2, "乙方应于每月五日前支付。"),
                heading(3, "4.1 逾期责任"),
                para(4, "逾期按日计收。"),
                heading(5, "4.1.1 滞纳金"),
                para(6, "滞纳金率为万分之五。"),
                heading(7, "（三） 特别约定"),
                para(8, "双方另有约定的从其约定。"),
                heading(9, "第二十三条 合同解除"),
                para(10, "协商解除。"));
        ClauseTreeBuilder.BuildResult result = ClauseTreeBuilder.build(units);

        // 「第二十二条」中文数字解析 → 22
        assertEquals(22, ClauseTreeBuilder.chineseToInt("二十二"));
        ClauseTreeBuilder.Heading h22 = findHeading(result, 1);
        assertEquals("22", h22.path());
        assertEquals(1, h22.level());
        assertEquals("租金支付", h22.title());

        // 4.1 二级；4.1.1 三级挂在 4.1 下；（三）挂在 4.1.1 下（最近前驱）
        ClauseTreeBuilder.Heading h41 = findHeading(result, 3);
        assertEquals("4.1", h41.path());
        assertEquals(2, h41.level());
        ClauseTreeBuilder.Heading h411 = findHeading(result, 5);
        assertEquals("4.1.1", h411.path());
        assertEquals(3, h411.level());
        ClauseTreeBuilder.Heading h3 = findHeading(result, 7);
        assertEquals("4.1.1.3", h3.path());
        assertEquals(4, h3.level());
        ClauseTreeBuilder.Heading h23 = findHeading(result, 9);
        assertEquals("23", h23.path());
        assertEquals(1, h23.level());

        // pathBySeq：条款内单元回填条款路径
        assertEquals("22", result.pathBySeq().get(2));
        assertEquals("4.1", result.pathBySeq().get(4));
        assertEquals("4.1.1", result.pathBySeq().get(6));
        assertEquals("4.1.1.3", result.pathBySeq().get(8));
        assertEquals("23", result.pathBySeq().get(10));

        // clause 覆盖区间（含嵌套子条款）：22 覆盖 1〜8（4.1/4.1.1/（三）为其子条款）；
        // 4.1 覆盖 3〜8；4.1.1 覆盖 5〜6；23 覆盖 9〜10
        assertEquals(8, clauseOf(result, "22").unitTo());
        assertEquals(8, clauseOf(result, "4.1").unitTo());
        assertEquals(8, clauseOf(result, "4.1.1").unitTo());
        assertEquals(10, clauseOf(result, "23").unitTo());

        // 父子关系：4.1.1 的 parentPath = 4.1；23 parentPath = null
        assertEquals("4.1", clauseOf(result, "4.1.1").parentPath());
        assertNull(clauseOf(result, "22").parentPath());
        assertNull(clauseOf(result, "23").parentPath());
    }

    @Test
    void blankInsideClauseMarksStatusBlank() {
        List<ParsedUnit> units = List.of(
                heading(1, "第三条 租金标准"),
                blank(2, "月租金：【 】"),
                para(3, "按月支付。"),
                heading(4, "第四条 押金"),
                para(5, "押金已付。"));
        ClauseTreeBuilder.BuildResult result = ClauseTreeBuilder.build(units);
        assertEquals("BLANK", clauseOf(result, "3").status());
        assertEquals("VALID", clauseOf(result, "4").status());
    }

    @Test
    void arabicTokenAndChineseParse() {
        assertEquals(3, ClauseTreeBuilder.parseNumberToken("三"));
        assertEquals(22, ClauseTreeBuilder.parseNumberToken("二十二"));
        assertEquals(11, ClauseTreeBuilder.parseNumberToken("十一"));
        assertEquals(5, ClauseTreeBuilder.parseNumberToken("5"));
        assertEquals(0, ClauseTreeBuilder.parseNumberToken(""));
    }

    private static ClauseTreeBuilder.Heading findHeading(ClauseTreeBuilder.BuildResult result, int seq) {
        for (var c : result.clauses()) {
            if (c.unitFrom() == seq) {
                return new ClauseTreeBuilder.Heading(seq, levelOfPath(c.clausePath()),
                        c.clausePath(), c.title());
            }
        }
        throw new AssertionError("clause from seq " + seq + " not found");
    }

    private static com.tioka.ontology.api.docparse.ParsedClause clauseOf(
            ClauseTreeBuilder.BuildResult result, String path) {
        for (var c : result.clauses()) {
            if (c.clausePath().equals(path)) {
                return c;
            }
        }
        throw new AssertionError("clause " + path + " not found");
    }

    private static int levelOfPath(String path) {
        return path.split("\\.").length;
    }
}

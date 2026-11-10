package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * UnitSegmenter（DOC-01 切分规则，M3-03 §2 L253-260 冻结；纯函数）：
 * <ul>
 *   <li>跨页段落合并：上一行不以句号结尾且下一页开头无编号时合并。</li>
 *   <li>标题：命中三类条款编号正则的行。</li>
 *   <li>表格：一个单元格一个单元（table_ref 记行列，合并单元格取左上角——上游保证）。</li>
 *   <li>签章区：「甲方（盖章）」「签订日期」之后的块标 SIGN。</li>
 *   <li>空白填写位 5 种写法标 BLANK_FIELD。</li>
 *   <li>所在部分：遇到「定义」「专用条款」「通用条款」「附件」标题时切换 section。</li>
 * </ul>
 */
public final class UnitSegmenter {

    // ---- 标题正则三类（M3-03 §2） ----
    public static final Pattern CLAUSE_CN =
            Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+条");
    public static final Pattern CLAUSE_NUMERIC =
            Pattern.compile("^\\d+(\\.\\d+){0,3}\\s");
    public static final Pattern CLAUSE_PAREN =
            Pattern.compile("^[（(][一二三四五六七八九十\\d]+[)）]");

    // ---- 空白填写位 5 种写法（M3-03 §2） ----
    public static final Pattern BLANK_BRACKET = Pattern.compile("【\\s*】");
    public static final Pattern BLANK_UNDERSCORE = Pattern.compile("_{3,}");
    public static final Pattern BLANK_FULLWIDTH_UNDERSCORE = Pattern.compile("＿{3,}");
    public static final Pattern BLANK_EMPTY_PAREN = Pattern.compile("[（(]\\s*[)）]");
    public static final Pattern BLANK_SLASH = Pattern.compile("^[／/\\s]+$");

    // ---- 签章区触发词 ----
    public static final Pattern SIGN_TRIGGER =
            Pattern.compile("(甲方[（(]盖章[)）]|乙方[（(]盖章[)）]|签订日期|签章)");

    // ---- section 切换标题 ----
    private static final String[][] SECTION_KEYWORDS = {
            {"定义", "DEF"},
            {"专用条款", "SPECIAL"},
            {"通用条款", "GENERAL"},
            {"附件", "ANNEX"},
    };

    private UnitSegmenter() {
    }

    /**
     * @param docId 单元 ID 前缀（unitId = {docId}:{pageNo}:{seq}）
     * @return 全局顺序单元（seq 从 1 开始；clausePath 由 ClauseTreeBuilder 回填）
     */
    public static List<ParsedUnit> segment(List<ParsedLine> lines, String docId) {
        List<ParsedUnit> units = new ArrayList<>();
        String section = null;
        boolean signZone = false;
        int seq = 0;

        for (int i = 0; i < lines.size(); i++) {
            ParsedLine line = lines.get(i);
            String text = line.text() == null ? "" : line.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            // COMMENT 行（批注）独立单元，进不了条款树
            if ("COMMENT".equals(line.kind())) {
                units.add(unit(docId, ++seq, line, ParseContract.T_COMMENT,
                        ParseContract.R_ANNOTATION, section, null));
                continue;
            }

            // section 切换（定义 / 专用条款 / 通用条款 / 附件）：独立标题行，不要求编号正则
            String newSection = sectionOf(text);
            if (newSection != null) {
                section = newSection;
                signZone = false;
                units.add(unit(docId, ++seq, line, ParseContract.T_HEADING, ParseContract.R_BODY,
                        section, text));
                continue;
            }

            // 签章区：触发词之后的块标 SIGN（触发词行本身也归入签章区）
            if (signZone && !isHeadingLike(text, line)) {
                units.add(unit(docId, ++seq, line, "SIGN", ParseContract.R_BODY, section, text));
                continue;
            }
            if (SIGN_TRIGGER.matcher(text).find()) {
                signZone = true;
                units.add(unit(docId, ++seq, line, "SIGN", ParseContract.R_BODY, section, text));
                continue;
            }

            // 表格单元格（上游保证合并单元格取左上角）
            if (line.tableRef() != null) {
                String kind = isBlankField(text) ? ParseContract.T_BLANK_FIELD : "CELL";
                units.add(unit(docId, ++seq, line, kind, ParseContract.R_BODY, section, text));
                continue;
            }

            // 标题：三类条款编号正则
            if (isHeadingLike(text, line)) {
                units.add(unit(docId, ++seq, line, ParseContract.T_HEADING,
                        ParseContract.R_BODY, section, text));
                continue;
            }

            // 空白填写位
            if (isBlankField(text)) {
                units.add(unit(docId, ++seq, line, ParseContract.T_BLANK_FIELD,
                        ParseContract.R_BODY, section, text));
                continue;
            }

            // 段落：跨页段落合并（上一行不以句号结尾、本行不在新页开头带编号）
            if (!units.isEmpty()) {
                ParsedUnit prev = units.get(units.size() - 1);
                if (canMerge(prev, text, line)) {
                    units.set(units.size() - 1, mergeInto(prev, text, line));
                    continue;
                }
            }
            units.add(unit(docId, ++seq, line, ParseContract.T_PARAGRAPH,
                    ParseContract.R_BODY, section, text));
        }
        return units;
    }

    private static ParsedUnit unit(String docId, int seq, ParsedLine line, String type,
                                   String role, String section, String text) {
        String t = text == null ? line.text() : text;
        String unitId = docId + ":" + line.pageNo() + ":" + seq;
        return new ParsedUnit(unitId, seq, type, role, null, 0, null, t,
                line.regions(), line.source(), line.confidence(), List.of(),
                line.tableRef(), section);
    }

    static boolean isHeadingLike(String text, ParsedLine line) {
        return line.tableRef() == null
                && (CLAUSE_CN.matcher(text).find()
                || CLAUSE_NUMERIC.matcher(text).find()
                || CLAUSE_PAREN.matcher(text).find());
    }

    static boolean isBlankField(String text) {
        return BLANK_BRACKET.matcher(text).find()
                || BLANK_UNDERSCORE.matcher(text).find()
                || BLANK_FULLWIDTH_UNDERSCORE.matcher(text).find()
                || BLANK_EMPTY_PAREN.matcher(text).find()
                || BLANK_SLASH.matcher(text).find();
    }

    private static String sectionOf(String text) {
        for (String[] kv : SECTION_KEYWORDS) {
            if (text.startsWith(kv[0]) || text.contains(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    /** 跨页段落合并判据（M3-03 §2）：上一单元 PARA、不以句号结尾；本行无编号开头；页不同。 */
    static boolean canMerge(ParsedUnit prev, String nextText, ParsedLine nextLine) {
        if (!ParseContract.T_PARAGRAPH.equals(prev.type())) {
            return false;
        }
        if (prevPageNo(prev) == pageNoOf(nextLine)) {
            return false;
        }
        if (isHeadingLike(nextText, nextLine) || isBlankField(nextText)) {
            return false;
        }
        String p = prev.text();
        return !(p.endsWith("。") || p.endsWith("；") || p.endsWith(";")
                || p.endsWith(".") || p.endsWith("！") || p.endsWith("！")
                || p.endsWith("？") || p.endsWith("？"));
    }

    private static int prevPageNo(ParsedUnit prev) {
        return prev.regions().isEmpty() ? DocxParser.WORD_PAGE_NO : prev.regions().get(0).pageNo();
    }

    private static int pageNoOf(ParsedLine line) {
        return line.pageNo();
    }

    private static ParsedUnit mergeInto(ParsedUnit prev, String nextText, ParsedLine nextLine) {
        String merged = prev.text() + nextText;
        List<com.tioka.ontology.api.docparse.ParsedRegion> regions = new ArrayList<>(prev.regions());
        regions.addAll(nextLine.regions());
        String source = prev.source();
        Double conf = prev.confidence();
        return new ParsedUnit(prev.blockId(), prev.seq(), prev.type(), prev.role(),
                prev.parentId(), prev.level(), prev.listLabel(), merged,
                regions, source, conf, prev.flags(), prev.tableRef(), prev.section());
    }
}

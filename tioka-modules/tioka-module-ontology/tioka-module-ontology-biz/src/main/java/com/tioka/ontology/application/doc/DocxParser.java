package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedTableRef;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumFmt;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DocxParser（DOC-01 / PRS-00，ADR-060 §2.1 Word 路线）：
 * <ul>
 *   <li>自动编号渲染：POI 段落文字不含编号，按 numbering 定义的级别、格式与计数器
 *       计算渲染文字，拼在正文前（条款树能否建出来的前提，调研 05 G1）。</li>
 *   <li>表格按单元格输出（含合并单元格取左上角，纵向合并的延续行跳过）。</li>
 *   <li>修订采纳：被删除内容（w:delText）不进正文（POI run 文本只读 w:t）；
 *       含修订痕迹的文档级打 HAS_REVISIONS、段落打 REVISED。</li>
 *   <li>批注不进正文，分离为 COMMENT 行（role=ANNOTATION）。</li>
 * </ul>
 * Word 块 M3 无页码与坐标：全部行 pageNo=1（登记偏差）。
 */
public class DocxParser {

    public static final int WORD_PAGE_NO = 1;
    public static final String FLAG_HAS_REVISIONS = "HAS_REVISIONS";

    /** @return 行序列 + 文档级 flags（HAS_REVISIONS） */
    public DocxOutput parse(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFNumbering numbering = doc.getNumbering();
            NumberingCounter counter = new NumberingCounter(numbering);
            boolean hasRevisions = false;
            List<ParsedLine> lines = new ArrayList<>();

            int tableId = 0;
            for (org.apache.poi.xwpf.usermodel.IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    if (hasRevisionMarks(p)) {
                        hasRevisions = true;
                    }
                    String label = counter.renderLabel(p);
                    String text = paragraphText(p);
                    if (text.isEmpty() && label.isEmpty()) {
                        continue;
                    }
                    String full = label.isEmpty() ? text : label + text;
                    lines.add(new ParsedLine(WORD_PAGE_NO, full, List.of(), "PARA", null,
                            com.tioka.ontology.api.docparse.ParseContract.S_NATIVE, null));
                } else if (el instanceof XWPFTable table) {
                    tableId++;
                    for (int r = 0; r < table.getRows().size(); r++) {
                        XWPFTableRow row = table.getRows().get(r);
                        for (int c = 0; c < row.getTableCells().size(); c++) {
                            XWPFTableCell cell = row.getTableCells().get(c);
                            if (isVerticalMergeContinuation(cell)) {
                                continue;
                            }
                            String text = cell.getText() == null ? "" : cell.getText().trim();
                            if (text.isEmpty()) {
                                continue;
                            }
                            lines.add(new ParsedLine(WORD_PAGE_NO, text, List.of(), "CELL",
                                    new ParsedTableRef(tableId, r, c),
                                    com.tioka.ontology.api.docparse.ParseContract.S_NATIVE, null));
                        }
                    }
                }
            }

            List<ParsedLine> comments = extractComments(doc);
            List<String> flags = new ArrayList<>();
            if (hasRevisions) {
                flags.add(FLAG_HAS_REVISIONS);
            }
            return new DocxOutput(lines, comments, flags);
        }
    }

    public record DocxOutput(List<ParsedLine> lines, List<ParsedLine> comments, List<String> flags) {}

    /** 批注 → COMMENT 行（不进正文）。 */
    private List<ParsedLine> extractComments(XWPFDocument doc) {
        List<ParsedLine> out = new ArrayList<>();
        try {
            org.apache.poi.xwpf.usermodel.XWPFComment[] comments = doc.getComments();
            if (comments == null || comments.length == 0) {
                return out;
            }
            for (org.apache.poi.xwpf.usermodel.XWPFComment c : comments) {
                String text = c.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String author = c.getAuthor() == null ? "" : c.getAuthor();
                out.add(new ParsedLine(WORD_PAGE_NO,
                        "[批注" + (author.isEmpty() ? "" : " " + author) + "] " + text.trim(),
                        List.of(), "COMMENT", null,
                        com.tioka.ontology.api.docparse.ParseContract.S_NATIVE, null));
            }
        } catch (Exception e) {
            // comments.xml 缺失或 POI 版本差异：批注分离为尽力而为，不阻断解析
        }
        return out;
    }

    private static boolean hasRevisionMarks(XWPFParagraph p) {
        return p.getCTP().sizeOfInsArray() > 0 || p.getCTP().sizeOfDelArray() > 0;
    }

    /**
     * 段落正文：修订采纳口径——POI 5.2.5 的 run.text() 会把 w:delText 一并带出
     * （实测登记），须按 CTR 身份排除 w:del 内的 run。
     */
    private static String paragraphText(XWPFParagraph p) {
        java.util.Set<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> deleted =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange del
                : p.getCTP().getDelList()) {
            deleted.addAll(java.util.Arrays.asList(del.getRArray()));
        }
        StringBuilder sb = new StringBuilder();
        for (org.apache.poi.xwpf.usermodel.XWPFRun r : p.getRuns()) {
            if (deleted.contains(r.getCTR())) {
                continue;
            }
            String t = r.text();
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.toString().trim();
    }

    /** 纵向合并单元格的延续行（vMerge val 缺省或 continue）跳过——合并单元格取左上角。 */
    private static boolean isVerticalMergeContinuation(XWPFTableCell cell) {
        if (cell.getCTTc() == null || cell.getCTTc().getTcPr() == null) {
            return false;
        }
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge vMerge =
                cell.getCTTc().getTcPr().getVMerge();
        return vMerge != null
                && (vMerge.getVal() == null
                || org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE.equals(vMerge.getVal()));
    }

    /**
     * 自动编号计数器与渲染（decimal / chineseCounting / letter / roman / bullet）。
     * 简化口径：低级别出现时重置更深级别计数器；跨文档从头计数（同一解析器版本下确定性）。
     */
    static final class NumberingCounter {
        private final XWPFNumbering numbering;
        private final Map<BigInteger, int[]> counters = new HashMap<>();

        NumberingCounter(XWPFNumbering numbering) {
            this.numbering = numbering;
        }

        String renderLabel(XWPFParagraph p) {
            BigInteger numId = p.getNumID();
            if (numId == null || numbering == null) {
                return "";
            }
            XWPFNum num = numbering.getNum(numId);
            if (num == null) {
                return "";
            }
            BigInteger abstractId = null;
            if (num.getCTNum() != null && num.getCTNum().getAbstractNumId() != null) {
                abstractId = num.getCTNum().getAbstractNumId().getVal();
            }
            if (abstractId == null || numbering == null) {
                return "";
            }
            XWPFAbstractNum abs = numbering.getAbstractNum(abstractId);
            if (abs == null) {
                return "";
            }
            CTAbstractNum ctAbs = abs.getCTAbstractNum();
            int lvl = p.getNumIlvl() == null ? 0 : p.getNumIlvl().intValue();
            int[] counts = counters.computeIfAbsent(abstractId, k -> new int[9]);
            CTLvl ctLevel = findLevel(ctAbs, lvl);
            int start = 1;
            if (ctLevel != null && ctLevel.isSetStart() && ctLevel.getStart().getVal() != null) {
                start = Math.max(1, ctLevel.getStart().getVal().intValue());
            }
            counts[lvl] = counts[lvl] == 0 ? start : counts[lvl] + 1;
            // 更深级别计数器复位（下级出现时，子编号重新计）
            for (int j = lvl + 1; j < counts.length; j++) {
                counts[j] = 0;
            }
            if (ctLevel == null) {
                return "";
            }
            String fmt = numFmt(ctLevel);
            String lvlText = ctLevel.getLvlText() == null ? "" : ctLevel.getLvlText().getVal();
            if (lvlText == null || lvlText.isEmpty()) {
                return "";
            }
            if ("bullet".equals(fmt) || "none".equals(fmt)) {
                // bullet 依 lvlText 原样（如 •）；none 无编号
                return "bullet".equals(fmt) ? lvlText : "";
            }
            return expand(lvlText, counts, ctAbs, lvl, fmt, start);
        }

        private String expand(String lvlText, int[] counts, CTAbstractNum ctAbs,
                              int currentLevel, String currentFmt, int start) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lvlText.length(); i++) {
                char ch = lvlText.charAt(i);
                if (ch == '%' && i + 1 < lvlText.length() && Character.isDigit(lvlText.charAt(i + 1))) {
                    int levelIdx = lvlText.charAt(i + 1) - '1';
                    i++;
                    int levelValue = levelIdx < counts.length && counts[levelIdx] > 0
                            ? counts[levelIdx] : start;
                    String levelFmt = levelIdx == currentLevel ? currentFmt : levelFmt(ctAbs, levelIdx);
                    out.append(format(levelFmt, levelValue));
                } else {
                    out.append(ch);
                }
            }
            return out.toString();
        }

        private static String levelFmt(CTAbstractNum ctAbs, int lvl) {
            CTLvl l = findLevel(ctAbs, lvl);
            return l == null ? "decimal" : numFmt(l);
        }

        private static CTLvl findLevel(CTAbstractNum ctAbs, int lvl) {
            for (CTLvl l : ctAbs.getLvlArray()) {
                if (l.getIlvl() != null && l.getIlvl().intValue() == lvl) {
                    return l;
                }
            }
            return null;
        }

        private static String numFmt(CTLvl l) {
            CTNumFmt f = l.getNumFmt();
            return f == null || f.getVal() == null ? "decimal" : f.getVal().toString();
        }

        static String format(String fmt, int value) {
            if (value <= 0) {
                return "";
            }
            return switch (fmt) {
                case "decimal", "decimalZero" -> String.valueOf(value);
                case "chineseCounting", "chineseCountingThousand" -> chinese(value, false);
                case "chineseLegalSimplified" -> chinese(value, true);
                case "lowerLetter" -> letter(value, false);
                case "upperLetter" -> letter(value, true);
                case "lowerRoman" -> roman(value, false);
                case "upperRoman" -> roman(value, true);
                default -> String.valueOf(value);
            };
        }

        static String chinese(int n, boolean legal) {
            String[] digits = legal
                    ? new String[]{"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"}
                    : new String[]{"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
            String[] units = legal
                    ? new String[]{"", "拾", "佰", "仟"}
                    : new String[]{"", "十", "百", "千"};
            if (n >= 10000) {
                return String.valueOf(n);
            }
            StringBuilder sb = new StringBuilder();
            int[] parts = new int[4];
            int idx = 0;
            while (n > 0) {
                parts[idx++] = n % 10;
                n /= 10;
            }
            boolean needZero = false;
            for (int i = idx - 1; i >= 0; i--) {
                int d = parts[i];
                if (d == 0) {
                    needZero = sb.length() > 0;
                    continue;
                }
                if (needZero) {
                    sb.append('零');
                    needZero = false;
                }
                if (d == 1 && i == 1 && sb.length() == 0) {
                    // 一十 → 十
                } else {
                    sb.append(digits[d]);
                }
                sb.append(units[i]);
            }
            return sb.toString();
        }

        static String letter(int n, boolean upper) {
            StringBuilder sb = new StringBuilder();
            while (n > 0) {
                n--;
                sb.insert(0, (char) ((upper ? 'A' : 'a') + n % 26));
                n /= 26;
            }
            return sb.toString();
        }

        static String roman(int n, boolean upper) {
            String[][] table = {{"1000", "M"}, {"900", "CM"}, {"500", "D"}, {"400", "CD"},
                    {"100", "C"}, {"90", "XC"}, {"50", "L"}, {"40", "XL"}, {"10", "X"},
                    {"9", "IX"}, {"5", "V"}, {"4", "IV"}, {"1", "I"}};
            StringBuilder sb = new StringBuilder();
            int rest = n;
            for (String[] pair : table) {
                int v = Integer.parseInt(pair[0]);
                while (rest >= v) {
                    sb.append(upper ? pair[1] : pair[1].toLowerCase());
                    rest -= v;
                }
            }
            return sb.toString();
        }
    }
}

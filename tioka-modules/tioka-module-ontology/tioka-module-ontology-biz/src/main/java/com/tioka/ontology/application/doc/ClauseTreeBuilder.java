package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedClause;
import com.tioka.ontology.api.docparse.ParsedUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ClauseTreeBuilder（DOC-01 条款树，M3-03 §2）：条款编号识别与树构建。
 * 「第X条」（一级）、「4.1.1」（点分，级数=点数+1）、「（三）」（括号序号，挂在当前父级下）混排时树结构正确。
 * 条款内含 BLANK_FIELD 单元 → status=BLANK（取值状态「空白」的来源）；含 REVISED → MODIFIED。
 */
public final class ClauseTreeBuilder {

    public record BuildResult(List<ParsedClause> clauses, Map<Integer, String> pathBySeq) {}

    record Heading(int seq, int level, String path, String title) {}

    private static final Pattern CN = Pattern.compile("^第([一二三四五六七八九十百零〇\\d]+)条\\s*");
    private static final Pattern NUMERIC = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})(?=[\\s、．.，,]|$)");
    private static final Pattern PAREN = Pattern.compile("^[（(]([一二三四五六七八九十\\d]+)[)）]\\s*");

    private ClauseTreeBuilder() {
    }

    public static BuildResult build(List<ParsedUnit> units) {
        List<Heading> headings = new ArrayList<>();
        Map<Integer, String> pathBySeq = new LinkedHashMap<>();

        // 一次扫描：识别标题 → clause_path 与层级
        for (ParsedUnit u : units) {
            if (!ParseContract.T_HEADING.equals(u.type()) || u.tableRef() != null) {
                continue;
            }
            String text = u.text() == null ? "" : u.text().trim();

            Matcher cn = CN.matcher(text);
            if (cn.find()) {
                int n = parseNumberToken(cn.group(1));
                headings.add(new Heading(u.seq(), 1, String.valueOf(n), remainder(text, cn.end())));
                continue;
            }
            Matcher num = NUMERIC.matcher(text);
            if (num.find()) {
                String path = num.group(1);
                int level = path.split("\\.").length;
                headings.add(new Heading(u.seq(), level, path, remainder(text, num.end())));
                continue;
            }
            Matcher paren = PAREN.matcher(text);
            if (paren.find()) {
                int n = parseNumberToken(paren.group(1));
                Heading nearest = null;
                for (Heading h : headings) {
                    if (h.seq() < u.seq() && (nearest == null || h.seq() > nearest.seq())) {
                        nearest = h;
                    }
                }
                String path = nearest == null
                        ? String.valueOf(n) : nearest.path() + "." + n;
                int level = nearest == null ? 1 : nearest.level() + 1;
                headings.add(new Heading(u.seq(), level, path,
                        remainder(text, paren.end())));
            }
        }

        // pathBySeq：标题自身 + 其覆盖区间内的单元
        List<ParsedClause> clauses = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading h = headings.get(i);
            int unitTo = lastUnitSeq(headings, i, h.level(), units);
            String parent = null;
            int parentSeq = -1;
            for (Heading other : headings) {
                if (other.seq() < h.seq() && other.level() < h.level()
                        && other.seq() > parentSeq) {
                    parent = other.path();
                    parentSeq = other.seq();
                }
            }
            String status = "VALID";
            for (ParsedUnit u : units) {
                if (u.seq() > h.seq() && u.seq() <= unitTo) {
                    pathBySeq.put(u.seq(), h.path());
                    if (ParseContract.T_BLANK_FIELD.equals(u.type())) {
                        status = "BLANK";
                    } else if (u.flags() != null && u.flags().contains(ParseContract.F_REVISED)) {
                        status = "MODIFIED";
                    }
                }
            }
            pathBySeq.put(h.seq(), h.path());
            clauses.add(new ParsedClause(h.path(), parent,
                    h.title() == null || h.title().isEmpty() ? null : h.title(),
                    h.seq(), unitTo, status, List.of()));
        }
        return new BuildResult(clauses, pathBySeq);
    }

    /** 条款覆盖终点：下一个级别 ≤ 本级的标题 seq − 1；无则最后一个单元。 */
    private static int lastUnitSeq(List<Heading> headings, int idx, int level,
                                   List<ParsedUnit> units) {
        int lastSeq = units.isEmpty() ? 0 : units.get(units.size() - 1).seq();
        int end = lastSeq;
        for (int j = idx + 1; j < headings.size(); j++) {
            if (headings.get(j).level() <= level) {
                end = headings.get(j).seq() - 1;
                break;
            }
        }
        return Math.max(end, headings.get(idx).seq());
    }

    private static String remainder(String text, int from) {
        return text.substring(from).trim();
    }

    /** 中文数字 / 阿拉伯数字 token → int（0 无法解析时返回 0）。 */
    static int parseNumberToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        if (token.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return chineseToInt(token);
    }

    static int chineseToInt(String s) {
        Map<Character, Integer> digits = Map.of(
                '零', 0, '一', 1, '二', 2, '三', 3, '四', 4, '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);
        Map<Character, Integer> units = Map.of(
                '十', 10, '百', 100, '千', 1000, '拾', 10, '佰', 100, '仟', 1000);
        int total = 0;
        int current = 0;
        for (char c : s.toCharArray()) {
            Integer d = digits.get(c);
            if (d != null) {
                current = current * 10 + d;
            } else {
                Integer u = units.get(c);
                if (u == null) {
                    return total + current;
                }
                total += (current == 0 ? 1 : current) * u;
                current = 0;
            }
        }
        return total + current;
    }
}

package com.tioka.knowledge.infrastructure.parse;

import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.api.docparse.ParsedUnit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 文本类格式（txt/md/markdown/html/htm）结构化解析（KNW-11）。
 *
 * <p>DOC 域 ParserRouter（M3 子集）只路由 docx/doc/pdf/image，文本格式会抛
 * {@code unsupported_mime}，故文本格式在 knowledge-biz 本地解析为 ParseResult：
 * Markdown 井号标题 → HEADING（level = 井号数）；Markdown 管道表 → TABLE（整表 Markdown 文本）；
 * HTML 剥离标签按块切段落；txt 按非空行切段落。空行/装饰行不产出。
 *
 * <p>无对象存储与 DOC 域参与：parseId = {@code local:{sha256 前 16 位}}，blockId = {@code t:{seq}}，
 * 页码恒为 1（文本无分页语义）。同样作为 ParserPort 缺席时的降级路径（条件装配缺席 → 服务侧
 * 用本类直接产 ParseResult，行为与旧同步路径「文本直解」等价）。
 */
public final class TextStructuredParser {

    public ParseResult parse(String fileName, String format, byte[] bytes) {
        String content = bytes == null || bytes.length == 0
                ? "" : new String(bytes, StandardCharsets.UTF_8);
        return parse(fileName, format, content);
    }

    public ParseResult parse(String fileName, String format, String content) {
        String fmt = ParserPort.resolveFormat(format != null ? format : fileName);
        List<ParsedUnit> units = switch (fmt) {
            case "md", "markdown" -> parseMarkdown(content);
            case "html", "htm" -> parseHtml(content);
            default -> parsePlain(content);
        };
        String sha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        return new ParseResult(ParseContract.CONTRACT, "local:" + sha.substring(0, 16), sha,
                "text-1.0.0", ParseContract.ST_READY, 1, null, units, List.of(), List.of());
    }

    /** Markdown：井号标题 / 管道表格（连续 | 行） / 其余按行段落。 */
    private List<ParsedUnit> parseMarkdown(String content) {
        List<ParsedUnit> units = new ArrayList<>();
        int seq = 0;
        List<String> tableRows = new ArrayList<>();
        for (String raw : content.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flushTable(units, tableRows, ++seq);
                continue;
            }
            if (line.startsWith("|") && line.endsWith("|")) {
                if (isTableDivider(line)) {
                    continue; // |---|---| 分隔行不进表格文本
                }
                tableRows.add(line);
                continue;
            }
            flushTable(units, tableRows, ++seq);
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                String text = line.substring(level).strip();
                if (!text.isEmpty()) {
                    units.add(unit(++seq, ParseContract.T_HEADING, ParseContract.R_BODY,
                            Math.min(level, 6), text));
                }
                continue;
            }
            units.add(unit(++seq, ParseContract.T_PARAGRAPH, ParseContract.R_BODY, 0, line));
        }
        flushTable(units, tableRows, ++seq);
        return units;
    }

    /** HTML：剥 script/style/标签，非空行段落。 */
    private List<ParsedUnit> parseHtml(String content) {
        String stripped = content.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?is)</(p|div|h[1-6]|li|tr)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("[ \\t]+", " ");
        List<ParsedUnit> units = new ArrayList<>();
        int seq = 0;
        for (String line : stripped.split("\\r?\\n", -1)) {
            String text = line.strip();
            if (!text.isEmpty()) {
                units.add(unit(++seq, ParseContract.T_PARAGRAPH, ParseContract.R_BODY, 0, text));
            }
        }
        return units;
    }

    /** 纯文本：非空行一段。 */
    private List<ParsedUnit> parsePlain(String content) {
        List<ParsedUnit> units = new ArrayList<>();
        int seq = 0;
        for (String line : content.split("\\r?\\n", -1)) {
            String text = line.strip();
            if (!text.isEmpty()) {
                units.add(unit(++seq, ParseContract.T_PARAGRAPH, ParseContract.R_BODY, 0, text));
            }
        }
        return units;
    }

    private void flushTable(List<ParsedUnit> units, List<String> rows, int seq) {
        if (rows.isEmpty()) {
            return;
        }
        units.add(unit(seq, ParseContract.T_TABLE, ParseContract.R_BODY, 0,
                String.join("\n", rows)));
        rows.clear();
    }

    private static boolean isTableDivider(String line) {
        return line.replaceAll("[|:\\-\\s]", "").isEmpty();
    }

    private static ParsedUnit unit(int seq, String type, String role, int level, String text) {
        return new ParsedUnit("t:" + seq, seq, type, role, null, level, null, text,
                List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null, List.of(), null, null);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

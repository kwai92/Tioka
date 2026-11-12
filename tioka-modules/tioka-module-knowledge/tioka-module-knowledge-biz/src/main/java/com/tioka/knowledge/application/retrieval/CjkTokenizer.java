package com.tioka.knowledge.application.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KNW-12 应用层中文分词（ADR-056 §2 步 4、ADR-018 回退口径）。
 *
 * <p>PostgreSQL 测试/生产镜像不保证装了 {@code pg_jieba} / {@code zhparser}，
 * 且 {@code to_tsvector('simple', ...)} 会把整句中文当成一个 token、无法命中部分词。
 * 因此把查询与文档文本都切成「中文二元组（bigram）+ ASCII 词」的空格分隔 token，
 * 全文召回 SQL 用 {@code strpos(lower(...), token) > 0} 逐个匹配并按命中数排序
 * （不用 LIKE，避免 token 中的 {@code %} / {@code _} 被当作通配符）。
 *
 * <p>例：「差旅报销流程」→ {@code 差旅 旅报 报销 销流 流程}；
 * 「A-03-2 编码」→ {@code a-03-2 编码}。同一函数用于查询与匹配侧，
 * 保证「只有字面命中、向量排名靠后」的条款能被独立全文一路召回。
 */
public final class CjkTokenizer {
    /** 单次检索最多下推的 token 数，防止 SQL 过长与全表扫描放大。 */
    public static final int MAX_TOKENS = 32;

    private CjkTokenizer() {}

    public static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                flushAscii(out, ascii);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                flushCjk(out, cjk);
                ascii.append(ch);
            } else {
                flushCjk(out, cjk);
                flushAscii(out, ascii);
            }
            if (out.size() >= MAX_TOKENS) {
                break;
            }
        }
        flushCjk(out, cjk);
        flushAscii(out, ascii);
        List<String> tokens = new ArrayList<>(out);
        return tokens.size() > MAX_TOKENS ? List.copyOf(tokens.subList(0, MAX_TOKENS)) : List.copyOf(tokens);
    }

    /** 中文连续串切 bigram；单字串保留单字。 */
    private static void flushCjk(Set<String> out, StringBuilder buf) {
        if (buf.isEmpty()) {
            return;
        }
        String s = buf.toString();
        buf.setLength(0);
        if (s.length() == 1) {
            out.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length() && out.size() < MAX_TOKENS; i++) {
            out.add(s.substring(i, i + 2));
        }
    }

    private static void flushAscii(Set<String> out, StringBuilder buf) {
        if (buf.isEmpty()) {
            return;
        }
        String s = buf.toString().replaceAll("^[-_]+|[-_]+$", "");
        buf.setLength(0);
        if (!s.isBlank() && out.size() < MAX_TOKENS) {
            out.add(s);
        }
    }

    private static boolean isCjk(char ch) {
        return (ch >= '\u3400' && ch <= '\u9fff')
                || (ch >= '\uf900' && ch <= '\ufaff')
                || (ch >= '\u3040' && ch <= '\u30ff');
    }
}

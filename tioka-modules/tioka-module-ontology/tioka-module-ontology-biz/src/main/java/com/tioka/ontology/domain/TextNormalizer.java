package com.tioka.ontology.domain;

/**
 * 引文归一化（DOC-03，M3-03 §0 domain 纯逻辑；不依赖 Spring）。
 * 归一口径：全半角、空白、标点、数字格式——{@code normalize(quote)} 与
 * {@code doc_unit.norm_text} 同源归一后做子串匹配。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            int cp = input.codePointAt(i);
            int charCount = Character.charCount(cp);
            i += charCount;

            // 零宽字符与软连字符剔除
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF || cp == 0x00AD) {
                continue;
            }
            // 全角 ASCII 区（FF01〜FF5E）与全角空格（FF00）转半角
            if (cp >= 0xFF01 && cp <= 0xFF5E) {
                cp = cp - 0xFEE0;
            } else if (cp == 0x3000) {
                cp = ' ';
            }
            // 各类引号/破折号统一
            cp = unifyPunctuation(cp);
            // 空白全部剔除（引文匹配不受空格/换行影响）
            if (Character.isWhitespace(cp)) {
                continue;
            }
            // 字母统一小写（数字与汉字原样保留）
            char ch = (char) Character.toLowerCase(cp);
            sb.append(ch);
        }
        return sb.toString();
    }

    private static int unifyPunctuation(int cp) {
        return switch (cp) {
            // 弯引号 → 直引号
            case 0x2018, 0x2019 -> '\'';
            case 0x201C, 0x201D -> '"';
            // 长破折号/短破折号 → 连字符
            case 0x2013, 0x2014, 0xFF0D -> '-';
            // 中文顿号/句号/冒号等保持原样（两侧同源归一，无需映射）
            default -> cp;
        };
    }
}

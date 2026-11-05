package com.tioka.knowledge.infrastructure.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 文档真解析：pdf→PDFBox，docx→POI，md/html/txt→文本规范化。
 */
@Component
public class DocumentParser {

    public String parse(byte[] bytes, String formatOrFilename) {
        if (bytes == null || bytes.length == 0) return "";
        String fmt = resolveFormat(formatOrFilename);
        return switch (fmt) {
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            case "html", "htm" -> normalizeHtml(new String(bytes, StandardCharsets.UTF_8));
            case "md", "markdown" -> normalizeMarkdown(new String(bytes, StandardCharsets.UTF_8));
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    public static String normalizeText(String content, String format) {
        if (content == null) return "";
        String fmt = resolveFormat(format);
        return switch (fmt) {
            case "html", "htm" -> normalizeHtml(content);
            case "md", "markdown" -> normalizeMarkdown(content);
            case "pdf", "docx" -> content.replaceAll("[^\\p{Print}\\n\\r\\t]", " ").replaceAll("\\s+", " ").trim();
            default -> content;
        };
    }

    public static String resolveFormat(String formatOrFilename) {
        if (formatOrFilename == null || formatOrFilename.isBlank()) return "txt";
        String s = formatOrFilename.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return s;
    }

    private static String parsePdf(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private static String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n"))
                    .trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeHtml(String content) {
        return content.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeMarkdown(String content) {
        return content.replaceAll("(?m)^#+\\s*", "").replaceAll("[*_`]", "");
    }
}

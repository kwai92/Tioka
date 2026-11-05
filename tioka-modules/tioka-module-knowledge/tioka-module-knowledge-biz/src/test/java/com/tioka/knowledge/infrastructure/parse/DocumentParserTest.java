package com.tioka.knowledge.infrastructure.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsePdfYieldsNonEmptyText() throws Exception {
        byte[] pdf = tinyPdf("Tioka PDF hello");
        String text = parser.parse(pdf, "sample.pdf");
        assertFalse(text.isBlank());
        assertTrue(text.contains("Tioka") || text.contains("hello") || text.contains("PDF"));
    }

    @Test
    void parseDocxYieldsNonEmptyText() throws Exception {
        byte[] docx = tinyDocx("Tioka DOCX hello world");
        String text = parser.parse(docx, "sample.docx");
        assertFalse(text.isBlank());
        assertTrue(text.contains("Tioka") || text.contains("DOCX"));
    }

    @Test
    void parseMarkdownAndHtml() {
        String md = parser.parse("# Title\n**bold**".getBytes(StandardCharsets.UTF_8), "note.md");
        assertTrue(md.contains("Title"));
        String html = parser.parse("<html><body><p>Hello</p></body></html>".getBytes(StandardCharsets.UTF_8), "a.html");
        assertTrue(html.contains("Hello"));
    }

    private static byte[] tinyPdf(String content) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(content);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] tinyDocx(String content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun run = p.createRun();
            run.setText(content);
            doc.write(out);
            return out.toByteArray();
        }
    }
}

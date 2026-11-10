package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParsedLine;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ParserRouter 缺省实现（M3-03 §2 改动清单）：按 MIME / 扩展名路由。
 * docx=DocxParser；doc=LibreOffice 转 docx 复用；pdf=PdfParser（文字层 + 密度判定）；
 * image=直接 OCR。未支持类型抛 IllegalArgumentException（FAILED unsupported_mime）。
 * 不用 @Component：依赖链（DocxParser/OcrClient）由 tioka.ontology.doc.enabled 条件装配
 * （OntologyDocAutoConfiguration），此处经该装配类 @Bean 注册。
 */
public class DefaultParserRouter implements ParserRouter {

    private final DocxParser docxParser;
    private final DocParser docParser;
    private final PdfParser pdfParser;
    private final ImageParser imageParser;

    public DefaultParserRouter(DocxParser docxParser,
                               @Value("${tioka.ontology.doc.soffice:soffice}") String sofficeCommand,
                               OcrClient ocrClient) {
        this.docxParser = docxParser;
        this.docParser = new DocParser(docxParser, sofficeCommand);
        this.pdfParser = new PdfParser(ocrClient);
        this.imageParser = new ImageParser(ocrClient);
    }

    public static String kindOf(String fileName, String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (m.contains("wordprocessingml.document")) {
            return "docx";
        }
        if (m.contains("msword") || m.contains("application/doc")) {
            return "doc";
        }
        if (m.contains("pdf")) {
            return "pdf";
        }
        if (m.startsWith("image/")) {
            return "image";
        }
        String ext = extension(fileName);
        return switch (ext) {
            case "docx" -> "docx";
            case "doc" -> "doc";
            case "pdf" -> "pdf";
            case "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff" -> "image";
            default -> "unknown";
        };
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public ParseOutput parse(ParseInput input) {
        String kind = kindOf(input.fileName(), input.mimeType());
        List<String> warnings = new ArrayList<>();
        return switch (kind) {
            case "docx" -> docx(input);
            case "doc" -> doc(input);
            case "pdf" -> pdf(input, warnings);
            case "image" -> image(input);
            default -> throw new IllegalArgumentException("unsupported_mime: " + input.mimeType());
        };
    }

    private ParseOutput docx(ParseInput input) {
        try {
            DocxParser.DocxOutput out = docxParser.parse(input.bytes());
            List<Page> pages = List.of(new Page(DocxParser.WORD_PAGE_NO,
                    com.tioka.ontology.api.docparse.ParseContract.K_NATIVE,
                    concat(out.lines(), out.comments()), null));
            return new ParseOutput(input.mimeType(), pages, out.flags());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("docx parse failed: " + e.getMessage(), e);
        }
    }

    private ParseOutput doc(ParseInput input) {
        try {
            DocxParser.DocxOutput out = docParser.parse(input.bytes());
            List<Page> pages = List.of(new Page(DocxParser.WORD_PAGE_NO,
                    com.tioka.ontology.api.docparse.ParseContract.K_NATIVE,
                    concat(out.lines(), out.comments()), null));
            List<String> warnings = new ArrayList<>(out.flags());
            warnings.add(".doc 经 LibreOffice 规范化");
            return new ParseOutput(input.mimeType(), pages, warnings);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null
                    ? "doc parse failed" : e.getMessage(), e);
        }
    }

    private ParseOutput pdf(ParseInput input, List<String> warnings) {
        try {
            List<Page> pages = pdfParser.parse(input.dataLabel(), input.spaceId(),
                    input.bytes(), warnings);
            return new ParseOutput(input.mimeType(), pages, warnings);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("pdf parse failed: " + e.getMessage(), e);
        }
    }

    private ParseOutput image(ParseInput input) {
        List<ParsedLine> lines = imageParser.parse(1, input.bytes(),
                input.dataLabel(), input.spaceId());
        List<Page> pages = List.of(new Page(1,
                com.tioka.ontology.api.docparse.ParseContract.K_IMAGE, lines, null));
        return new ParseOutput(input.mimeType(), pages, List.of());
    }

    private static List<com.tioka.ontology.api.docparse.ParsedLine> concat(
            List<com.tioka.ontology.api.docparse.ParsedLine> a,
            List<com.tioka.ontology.api.docparse.ParsedLine> b) {
        List<com.tioka.ontology.api.docparse.ParsedLine> all =
                new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }
}

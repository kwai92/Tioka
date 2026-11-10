package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PdfParser（DOC-01）：PDFBox 逐页取文字层（带行级 bbox）；字符密度 &lt; 50 字/页或乱码率
 * &gt; 20% 的页判 SCANNED 进 OCR 队列（页渲染 PNG 200 DPI 经 OcrClient），回来后 OcrMerger 合并。
 */
public class PdfParser {

    /** 缺省文字层可信阈值：50 字/页（M3-03 §2）。 */
    public static final double MIN_CHARS_PER_PAGE = 50d;
    /** 乱码率上限（M3-03 §2）。 */
    public static final double MAX_GARBLED_RATIO = 0.20d;
    /** 页渲染 DPI（DOC-02）。 */
    public static final float RENDER_DPI = 200f;

    private final OcrClient ocrClient;

    public PdfParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public List<ParserRouter.Page> parse(String dataLabel, String spaceId, byte[] bytes,
                                         List<String> warnings) throws Exception {
        List<ParserRouter.Page> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                int pageNo = i + 1;
                PDPage page = doc.getPage(i);
                float width = page.getMediaBox().getWidth();
                float height = page.getMediaBox().getHeight();

                LineCollector stripper = new LineCollector(width, height, pageNo);
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                stripper.setSortByPosition(true);
                String text = stripper.getText(doc);
                List<ParsedLine> textLines = stripper.collectedLines(pageNo);

                int visibleChars = countVisible(text);
                double density = visibleChars;
                double garbled = garbledRatio(text);
                boolean textTrusted = density >= MIN_CHARS_PER_PAGE && garbled <= MAX_GARBLED_RATIO;

                List<ParsedLine> pageLines;
                String kind;
                if (textTrusted) {
                    kind = ParseContract.K_TEXT_PDF;
                    pageLines = textLines;
                } else {
                    kind = ParseContract.K_SCANNED;
                    if (ocrClient == null) {
                        throw new IllegalStateException(
                                "ocr_unavailable: 第 " + pageNo + " 页无可信文字层且未装配 OCR");
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ImageIO.write(renderer.renderImageWithDPI(i, RENDER_DPI), "png", bos);
                    List<ParsedLine> ocrLines = ocrClient.ocrPage(pageNo, bos.toByteArray(), dataLabel, spaceId);
                    pageLines = OcrMerger.merge(textLines, ocrLines);
                    warnings.add("page " + pageNo + " 文字层不可信（密度 " + (int) density
                            + " 字/页，乱码率 " + String.format("%.2f", garbled) + "），已 OCR");
                }
                pages.add(new ParserRouter.Page(pageNo, kind, pageLines, density));
            }
        }
        return pages;
    }

    static int countVisible(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                n++;
            }
        }
        return n;
    }

    /** 乱码率：替换符与不可打印控制字符占比。 */
    static double garbledRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0d;
        }
        int bad = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (c == '\uFFFD' || (Character.isISOControl(c))) {
                bad++;
            }
        }
        return total == 0 ? 0d : (double) bad / total;
    }

    /** 逐行收集文字层文本与页内 0〜1 归一化 bbox。 */
    private static final class LineCollector extends PDFTextStripper {
        private final float width;
        private final float height;
        private final int pageNo;
        private final List<ParsedLine> lines = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private float x0 = Float.MAX_VALUE;
        private float x1 = 0f;
        private float y0 = Float.MAX_VALUE;
        private float y1 = 0f;

        LineCollector(float width, float height, int pageNo) throws java.io.IOException {
            super();
            this.width = width;
            this.height = height;
            this.pageNo = pageNo;
        }

        List<ParsedLine> collectedLines(int ignoredPageNo) {
            flush();
            return List.copyOf(lines);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
            if (positions != null && !positions.isEmpty()) {
                for (TextPosition tp : positions) {
                    x0 = Math.min(x0, tp.getXDirAdj());
                    x1 = Math.max(x1, tp.getXDirAdj() + tp.getWidthDirAdj());
                    y0 = Math.min(y0, tp.getYDirAdj() - tp.getHeightDir());
                    y1 = Math.max(y1, tp.getYDirAdj());
                }
            }
            current.append(text);
            // 必须回调 super 才会写入内部输出流，否则 getText() 返回空串（密度恒 0）
            super.writeString(text, positions);
        }

        @Override
        protected void writeLineSeparator() throws java.io.IOException {
            flush();
            super.writeLineSeparator();
        }

        @Override
        protected void writeParagraphSeparator() throws java.io.IOException {
            flush();
            super.writeParagraphSeparator();
        }

        private void flush() {
            String text = current.toString().trim();
            if (!text.isEmpty() && width > 0 && height > 0 && x1 > x0 && y1 > y0) {
                ParsedRegion region = new ParsedRegion(pageNo,
                        clamp(x0 / width), clamp(y0 / height),
                        clamp(x1 / width), clamp(y1 / height));
                lines.add(new ParsedLine(pageNo, text, List.of(region), "PARA", null,
                        ParseContract.S_TEXT_LAYER, null));
            }
            current.setLength(0);
            x0 = Float.MAX_VALUE;
            x1 = 0f;
            y0 = Float.MAX_VALUE;
            y1 = 0f;
        }

        private static double clamp(double v) {
            return Math.max(0d, Math.min(1d, v));
        }
    }
}

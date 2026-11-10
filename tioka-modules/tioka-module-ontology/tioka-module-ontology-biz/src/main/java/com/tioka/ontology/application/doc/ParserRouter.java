package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParsedLine;

import java.util.List;

/**
 * 解析器路由（M3-03 §2 DOC-01）：按 MIME 选解析器，产出「行」序列。
 * 页级 OCR（低密度页 / 图片页）在解析器内部经 {@link OcrClient} 完成并合并。
 */
public interface ParserRouter {

    ParseOutput parse(ParseInput input);

    record ParseInput(String fileName, String mimeType, byte[] bytes,
                      String dataLabel, String spaceId) {}

    record Page(int pageNo, String kind, List<ParsedLine> lines, Double charDensity) {}

    record ParseOutput(String mimeType, List<Page> pages, List<String> warnings) {
        public ParseOutput {
            pages = pages == null ? List.of() : List.copyOf(pages);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

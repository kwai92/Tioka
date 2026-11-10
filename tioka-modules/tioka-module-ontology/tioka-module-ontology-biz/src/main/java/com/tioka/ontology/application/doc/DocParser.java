package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParsedLine;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DocParser（DOC-01）：.doc 经 LibreOffice headless 转 docx 后复用 {@link DocxParser}。
 * {@code soffice --headless --convert-to docx}，超时或转换失败抛异常（DocumentService 标 FAILED）。
 * LibreOffice 环境不可用时按「.doc 解析归环境依赖」处理（登记偏差）。
 */
public class DocParser {

    /** 转换超时（秒）。 */
    public static final long TIMEOUT_SECONDS = 60;
    public static final String ENV_DEPENDENCY_HINT = "doc_convert_failed（LibreOffice 环境依赖）";

    private final DocxParser docxParser;
    private final String sofficeCommand;

    public DocParser(DocxParser docxParser, String sofficeCommand) {
        this.docxParser = docxParser;
        this.sofficeCommand = sofficeCommand == null || sofficeCommand.isBlank()
                ? "soffice" : sofficeCommand;
    }

    /**
     * @param docx 转换结果（行序列 + 批注行 + flags）
     * @throws IOException soffice 不存在或超时
     */
    public DocxParser.DocxOutput parse(byte[] bytes) throws Exception {
        Path dir = Files.createTempDirectory("tioka-doc-");
        Path source = dir.resolve("input.doc");
        Files.write(source, bytes);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    sofficeCommand, "--headless", "--convert-to", "docx",
                    "--outdir", dir.toString(), source.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(ENV_DEPENDENCY_HINT + ": 转换超时 " + TIMEOUT_SECONDS + "s");
            }
            if (process.exitValue() != 0) {
                throw new IOException(ENV_DEPENDENCY_HINT + ": exit=" + process.exitValue());
            }
            Path docx = dir.resolve("input.docx");
            if (!Files.exists(docx)) {
                throw new IOException(ENV_DEPENDENCY_HINT + ": 未产出 docx");
            }
            return docxParser.parse(Files.readAllBytes(docx));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                throw new IOException(ENV_DEPENDENCY_HINT + ": soffice 不可用", e);
            }
            throw e;
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 便捷重载：批注行合并进正文行序列之后（DocumentService 只消费行序列）。 */
    public List<ParsedLine> parseLines(byte[] bytes) throws Exception {
        DocxParser.DocxOutput out = parse(bytes);
        List<ParsedLine> all = new ArrayList<>(out.lines());
        all.addAll(out.comments());
        return all;
    }
}

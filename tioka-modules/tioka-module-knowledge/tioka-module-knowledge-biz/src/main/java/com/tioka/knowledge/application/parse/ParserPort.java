package com.tioka.knowledge.application.parse;

import com.tioka.ontology.api.docparse.ParseResult;

/**
 * KNW-11：解析端口——knowledge-biz 与 DOC 域共用解析服务之间的防腐接口。
 * 输出直接复用 ontology-api docparse 的 {@link ParseResult}（knowledge-api 不重复定义契约）。
 *
 * <p>实现方（{@code DocParserAdapter}）对二进制格式（pdf/docx/doc/图片）经
 * {@code DocumentApi.ingest(FileRef) + units(docId)} 走 DOC 域（「同一文件只解析一次」由
 * DocumentService 去重键 {@code sha256 + parser_version + space_id} 承载）；文本类格式
 * （txt/md/markdown/html/htm）由 {@code TextStructuredParser} 本地结构化解析
 * （DOC 域 ParserRouter M3 子集不路由文本格式，登记口径）。
 *
 * <p>装配纪律：经 {@code KnowledgeParseAutoConfiguration} 条件装配
 * （DocumentApi 与 ObjectStorageApi 均在位时才注册）；服务侧一律 {@code ObjectProvider} 弱依赖。
 */
public interface ParserPort {

    /**
     * 解析文件字节为结构化产物。
     *
     * @param request 文件字节 + 文件名 + 格式 + workspaceId（作为 DOC 域 spaceId 去重维度）
     * @return 结构化解析产物（status = READY / PARTIAL；FAILED 以异常表达，交按阶段重试）
     * @throws RuntimeException 解析失败（含 DOC 域 FAILED），由入库流水线按阶段重试
     */
    ParseResult parse(ParseRequest request);

    record ParseRequest(String workspaceId, String fileName, String format, byte[] bytes) {
    }

    /** 格式归一（与原 DocumentParser.resolveFormat 口径一致）。 */
    static String resolveFormat(String formatOrFilename) {
        if (formatOrFilename == null || formatOrFilename.isBlank()) {
            return "txt";
        }
        String s = formatOrFilename.trim().toLowerCase(java.util.Locale.ROOT);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            s = s.substring(dot + 1);
        }
        return s;
    }

    /** 文本类格式：不经 DOC 域对象存储，本地结构化解析。 */
    static boolean isTextFormat(String format) {
        return switch (format == null ? "txt" : format) {
            case "txt", "text", "md", "markdown", "html", "htm" -> true;
            default -> false;
        };
    }
}

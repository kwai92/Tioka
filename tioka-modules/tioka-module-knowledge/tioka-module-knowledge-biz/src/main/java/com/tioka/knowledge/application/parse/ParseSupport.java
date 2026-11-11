package com.tioka.knowledge.application.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KNW-11：解析调用公共支撑（同步路径与异步流水线共用）。
 * ParserPort 经 ObjectProvider 弱依赖（条件装配缺席 → TextStructuredParser 本地结构化降级，
 * 行为与旧同步路径「文本直解」等价）。
 */
public final class ParseSupport {

    private ParseSupport() {
    }

    public static ParseResult parse(ObjectProvider<ParserPort> port, ParserPort.ParseRequest request) {
        ParserPort p = port == null ? null : port.getIfAvailable();
        if (p != null) {
            return p.parse(request);
        }
        return new com.tioka.knowledge.infrastructure.parse.TextStructuredParser()
                .parse(request.fileName(), request.format(), request.bytes());
    }

    /** 解析失败（FAILED 产物）→ 抛异常，交按阶段重试（≤3）与 FAILED 落库。 */
    public static void assertNotFailed(ParseResult result) {
        if (ParseContract.ST_FAILED.equals(result.status())) {
            throw new IllegalStateException("解析失败: "
                    + String.join("; ", result.warnings()));
        }
    }

    /** KNW-11：解析产物溯源与质量指标落 kb_document（parseId / 方案键与版本 / 状态 / 指标 / 警告）。 */
    public static void applyParseMeta(KbDocumentDO doc, ParseResult result, ObjectMapper mapper) {
        doc.setParseId(result.parseId());
        doc.setParsePlan(result.contract());
        doc.setParsePlanVersion(result.parserVersion());
        doc.setParseStatus(result.status());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pageCount", result.pageCount());
        metrics.put("unitCount", result.units().size());
        metrics.put("clauseCount", result.clauses().size());
        metrics.put("warningCount", result.warnings().size());
        doc.setQualityMetricsJson(writeJson(mapper, metrics));
        doc.setParseWarnings(result.warnings().isEmpty() ? null : writeJson(mapper, result.warnings()));
    }

    private static String writeJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}

package com.tioka.ontology.api.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Schema 声明的兜底值（ADR-066 §7.3 / 严格档 S7）。
 *
 * <p>只有用 {@code x-tioka-fallback} 声明了兜底的字段可以取兜底值，取值必须是 Schema 声明的
 * 「未知 / 待复核」状态（发布期 S7 已校验其属于该字段的 {@code enum}）。
 *
 * @param pointer JSON Pointer（形如 {@code /conclusion}）
 * @param value   Schema 声明的兜底取值
 */
public record FallbackDeclaration(String pointer, JsonNode value) {

    /** 该声明的路径是否覆盖给定的错误路径（同路径或为其祖先对象路径）。 */
    public boolean covers(String errorPointer) {
        if (errorPointer == null || pointer == null) {
            return false;
        }
        return errorPointer.equals(pointer) || errorPointer.startsWith(pointer + "/");
    }
}

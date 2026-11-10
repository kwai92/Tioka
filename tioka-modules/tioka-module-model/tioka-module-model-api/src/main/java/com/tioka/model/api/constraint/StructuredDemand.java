package com.tioka.model.api.constraint;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 结构化输出需求（SCH-02 接线，ADR-037 / ADR-066）：Runtime 侧只声明
 * 权威 Schema 与期望的承载形态，方言编译与方式选择在 Model Hub
 * （{@code StructuredRequestBuilder}，按实际路由到的模型能力）完成。
 *
 * @param schemaRef          权威 Schema 引用（如 {@code lease.finding@1}），随 {@code producedBy} 记账
 * @param authoritativeSchema 权威 Schema（校验永远用它，发模型的是编译后的方言）
 * @param emitAsTool         true = 以 {@code emit_output} 工具承载（OutputContract.mode=TOOL）；
 *                           false = 按能力优先 schemaRequest / jsonRequest
 */
public record StructuredDemand(String schemaRef, JsonNode authoritativeSchema, boolean emitAsTool) {
}

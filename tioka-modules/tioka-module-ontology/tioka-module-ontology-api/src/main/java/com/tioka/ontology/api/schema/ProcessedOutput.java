package com.tioka.ontology.api.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 输出流水线第 1〜4 步结果（ADR-066 §5 / SCH-07）。
 *
 * <p>成功时 {@code payload} 为经白名单规整后的 JSON 对象、{@code failure} 为 {@code null}；
 * 失败时 {@code payload} 为 {@code null}、{@code failure} 为原因码、{@code lastCandidate}
 * 为可解析的候选输出（不可消费，仅供排查与人工补录预填）。
 *
 * @param payload        规整后的对象；失败为 {@code null}
 * @param failure        原因码；{@code null} 表示通过第 1〜4 步
 * @param errors         对应 {@code failure} 的错误清单
 * @param normalizations 第 4 步规整记录（写入 {@code producedBy.normalizations}）
 * @param lastCandidate  失败时的候选输出；不可解析为 {@code null}
 */
public record ProcessedOutput(
        JsonNode payload,
        ReasonCode failure,
        List<SchemaError> errors,
        List<String> normalizations,
        JsonNode lastCandidate
) {

    public ProcessedOutput {
        errors = errors == null ? List.of() : List.copyOf(errors);
        normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
    }

    public boolean ok() {
        return failure == null;
    }

    public static ProcessedOutput success(JsonNode payload, List<String> normalizations) {
        return new ProcessedOutput(payload, null, List.of(), normalizations, null);
    }

    public static ProcessedOutput failed(ReasonCode reason, SchemaError error, JsonNode lastCandidate) {
        return new ProcessedOutput(null, reason, List.of(error), List.of(), lastCandidate);
    }
}

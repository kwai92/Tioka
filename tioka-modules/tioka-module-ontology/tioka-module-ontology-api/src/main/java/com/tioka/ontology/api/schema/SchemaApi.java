package com.tioka.ontology.api.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Schema 登记表对外 API（SCH-01）。
 */
public interface SchemaApi {

    /** ref 形如 {@code lease.finding@3} */
    Optional<SchemaDef> get(String ref);

    List<SchemaError> validate(String ref, JsonNode payload);

    /**
     * 对声明了 x-tioka-evidence 的对象做证据绑定检查。
     * SCH-01 提供签名与最小实现；完整 DOC 单元校验随文档层加深。
     */
    List<SchemaError> checkEvidence(String ref, JsonNode payload, List<String> visibleDocIds);

    /**
     * 执行 Schema 的语义规则 {@code x-tioka-rules}（ADR-066 §5.1 / SCH-08）。
     * 缺省无规则；返回的 {@code ERROR} 级违规进入修复，{@code WARN} 级不阻断。
     */
    default List<SchemaError> checkRules(String ref, JsonNode payload) {
        return List.of();
    }

    /**
     * 执行 Schema 的引用完整性 {@code x-tioka-ref}（ADR-066 §5.2 / SCH-08）。
     * 缺省跳过；无对应类型解析器时该类型跳过并记日志。
     */
    default List<SchemaError> checkRefs(String ref, JsonNode payload, List<String> visibleDocIds) {
        return List.of();
    }

    /**
     * {@code WARN} 级语义规则的路径清单（ADR-066 §5.1 / SCH-08）。
     * 非空时结局为 {@code VALID_WITH_UNKNOWN}，路径记入 {@code unknownPaths}。缺省无。
     */
    default List<String> warnPaths(String ref, JsonNode payload) {
        return List.of();
    }

    /**
     * 非严格档判定（ADR-066 §3 / SCH-06）：该 Schema 是否声明为
     * {@code x-tioka-tier: relaxed}。缺省 {@code false}（未接入严格档时视为严格档）。
     * 消费方（Agent 的 {@code output}）引用非严格档 Schema 时须显式声明 {@code allowRelaxedSchema}。
     */
    default boolean relaxedTier(String ref) {
        return false;
    }

    /**
     * Schema 声明的兜底值清单（ADR-066 §7.3 / SCH-09）：扫描 {@code x-tioka-fallback}
     * 声明的字段，返回其 JSON Pointer 与兜底取值。缺省无。
     *
     * <p>仅这些字段在「其余字段全通过、只有兜底字段有错」时允许取兜底值 → {@code VALID_WITH_UNKNOWN}。
     */
    default List<FallbackDeclaration> fallbackDeclarations(String ref) {
        return List.of();
    }

    /**
     * 输出流水线第 1〜4 步（ADR-066 §5 / SCH-07）：完成状态 → 提取 → 严格解析 → 白名单规整。
     *
     * <p>实现方（{@code SchemaRegistry}）保证：任何输入都以 {@link ProcessedOutput} 返回，
     * 不抛异常（内部异常转 {@link ReasonCode#INTERNAL}）。
     *
     * @return {@code null} 表示该实现未接入流水线（未启用 Schema 通路），调用方回退旧解析路径。
     */
    default ProcessedOutput process(String ref, RawModelOutput raw) {
        return null;
    }
}

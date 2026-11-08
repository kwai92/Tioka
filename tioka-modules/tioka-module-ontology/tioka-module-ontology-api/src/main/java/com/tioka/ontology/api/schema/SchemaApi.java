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
}

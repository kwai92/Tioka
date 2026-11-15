package com.tioka.model.infrastructure;

import com.tioka.model.api.profile.StructuredCapabilityQueryApi;

import java.util.Optional;

/**
 * SCH-06（ADR-066 §4；V177）：模型结构化输出能力记录的持久化出入口。
 *
 * <p>{@code model_ref} 口径 = {@code logicalModelId@modelName}（与 ModelBindingResolver 一致）。
 */
public interface ModelStructuredCapabilityRepository {

    /** 未登记返回 {@link Optional#empty()}（调用方按 legacy 兜底）。 */
    Optional<StructuredCapabilityQueryApi.CapabilityRecord> find(String modelRef);

    /** 幂等登记/覆盖（一致性测试与适配器预填写回，ADR-066 §4）。 */
    void put(String modelRef, StructuredCapabilityQueryApi.CapabilityRecord record);
}

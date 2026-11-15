package com.tioka.model.api.profile;

import java.util.Optional;

/**
 * SCH-06（ADR-066 §4）：按模型 ref 查结构化输出能力记录。
 *
 * <p>未登记时返回 {@link Optional#empty()}，调用方按
 * {@link StructuredOutputCapability#legacy(String)} 兜底。
 * 存储随 V177 落地（{@code model_structured_capability}），默认实现见 model-biz
 * {@code StructuredCapabilityQueryService}。
 */
public interface StructuredCapabilityQueryApi {

    Optional<CapabilityRecord> current(String modelRef);

    /** 能力记录 + 版本；版本变化即视为新记录，编译出的方言随之变化（ADR-066 §2）。 */
    record CapabilityRecord(StructuredOutputCapability capability, String version) {
        public CapabilityRecord {
            if (capability == null) {
                throw new IllegalArgumentException("capability required");
            }
        }
    }
}

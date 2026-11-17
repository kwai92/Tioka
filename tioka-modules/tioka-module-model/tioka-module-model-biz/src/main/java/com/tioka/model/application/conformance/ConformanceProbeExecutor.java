package com.tioka.model.application.conformance;

import com.tioka.model.api.profile.StructuredOutputMode;

/**
 * SCH-10（ADR-066 §10.2）：一致性探针的执行口。
 *
 * <p>「模型接入测试」对每个模型版本逐生成方式运行 {@link ConformanceProbeSet 一致性测试集}；
 * 真实模型调用由本 SPI 的实现方完成（供应商适配器，或 CI 闸门下的录制回放），
 * 平台侧只描述「发什么、要求什么」。
 *
 * <p>未装配实现时 {@code ModelConformanceRunner} 静默跳过（返回空结果），
 * 与 ADR-066 §10.4 的「模型启用结构化能力」闸门一致：本地构建不因缺少真实模型而失败。
 */
public interface ConformanceProbeExecutor {

    /** 以指定生成方式对指定模型执行一条探针。 */
    ProbeOutcome execute(String modelRef, StructuredOutputMode mode, ConformanceProbe probe);

    /** 探针结果：是否通过 + 失败原因码（通过时恒为 null）。 */
    record ProbeOutcome(boolean passed, String reasonCode) {

        public ProbeOutcome {
            if (passed) {
                reasonCode = null;
            }
        }

        public static ProbeOutcome pass() {
            return new ProbeOutcome(true, null);
        }

        public static ProbeOutcome fail(String reasonCode) {
            return new ProbeOutcome(false, reasonCode == null || reasonCode.isBlank()
                    ? "CONFORMANCE_FAILED" : reasonCode);
        }
    }
}

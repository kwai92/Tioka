package com.tioka.runtime.api.metrics;

import java.time.Instant;
import java.util.List;

/**
 * SCH-10（ADR-066 §10.1）：结构化输出指标聚合。
 *
 * <p>按（{@code schemaRef}、模型、生成方式）统计各结局比例与平均修复轮数；数据来源为
 * {@code runtime_item} 上由 OutputStage 写入的投影列（V232 的 {@code schema_ref} /
 * {@code outcome} / {@code reason_code} 与 V234 的 {@code model_ref} / {@code output_mode} /
 * {@code repair_rounds}），仅 {@code type='data'} 的 item 参与。
 *
 * <p>本接口只做聚合投影，不落库；消费方为告警信号源（{@code StructuredOutputAlertProvider}）
 * 与统计接口（MH-11 / FE-43）。
 */
public interface StructuredOutputStatsApi {

    /**
     * 窗口 {@code [from, to)} 内按（schemaRef, 模型, 生成方式）聚合。
     *
     * @param from 起始（含）；null 表示不限下界
     * @param to   结束（不含）；null 表示不限上界
     */
    List<Bucket> aggregate(Instant from, Instant to);

    /**
     * 一个（schemaRef, 模型, 生成方式）桶。
     *
     * <p>各结局计数互斥；{@code firstPass} 为「不经修复即 {@code VALID}」（{@code repair_rounds} 缺省 0）。
     */
    record Bucket(
            String schemaRef,
            String modelRef,
            String outputMode,
            long total,
            long firstPass,
            long finalPass,
            long fallback,
            long humanPending,
            long failed,
            double avgRepairRounds) {

        /** 首次通过率：不经修复即 {@code VALID} 的比例。 */
        public double firstPassRate() {
            return rate(firstPass);
        }

        /** 最终通过率：{@code VALID} + {@code VALID_WITH_UNKNOWN} 的比例。 */
        public double finalPassRate() {
            return rate(finalPass);
        }

        /** 兜底率：结局为 {@code VALID_WITH_UNKNOWN} 的比例。 */
        public double fallbackRate() {
            return rate(fallback);
        }

        /** 人工补录率：结局为 {@code HUMAN_PENDING} 的比例。 */
        public double humanRate() {
            return rate(humanPending);
        }

        /** 失败率：结局为 {@code FAILED} 的比例。 */
        public double failureRate() {
            return rate(failed);
        }

        private double rate(long n) {
            return total <= 0L ? 0d : (double) n / (double) total;
        }
    }
}

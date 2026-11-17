package com.tioka.runtime.infrastructure.persistence;

/**
 * SCH-10（ADR-066 §10.1）：{@code runtime_item} 指标聚合行（仅 {@code type='data'}）。
 *
 * <p>对应 {@code RuntimeItemStatsMapper#aggregateDataItems} 的按（schemaRef, 模型, 生成方式）分组结果。
 */
public class RuntimeItemStatsRow {

    private String schemaRef;
    private String modelRef;
    private String outputMode;
    private Long total;
    private Long firstPass;
    private Long finalPass;
    private Long fallback;
    private Long humanPending;
    private Long failed;
    private Double avgRepairRounds;

    public String getSchemaRef() { return schemaRef; }
    public void setSchemaRef(String schemaRef) { this.schemaRef = schemaRef; }
    public String getModelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }
    public String getOutputMode() { return outputMode; }
    public void setOutputMode(String outputMode) { this.outputMode = outputMode; }
    public Long getTotal() { return total; }
    public void setTotal(Long total) { this.total = total; }
    public Long getFirstPass() { return firstPass; }
    public void setFirstPass(Long firstPass) { this.firstPass = firstPass; }
    public Long getFinalPass() { return finalPass; }
    public void setFinalPass(Long finalPass) { this.finalPass = finalPass; }
    public Long getFallback() { return fallback; }
    public void setFallback(Long fallback) { this.fallback = fallback; }
    public Long getHumanPending() { return humanPending; }
    public void setHumanPending(Long humanPending) { this.humanPending = humanPending; }
    public Long getFailed() { return failed; }
    public void setFailed(Long failed) { this.failed = failed; }
    public Double getAvgRepairRounds() { return avgRepairRounds; }
    public void setAvgRepairRounds(Double avgRepairRounds) { this.avgRepairRounds = avgRepairRounds; }
}

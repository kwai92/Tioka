package com.tioka.ontology.api.schema;

/**
 * 模型原始输出与完成状态信号（ADR-066 §5 第 1 步 / SCH-07）。
 *
 * <p>完成状态由供应商适配器按 {@code refusalSignal} / {@code truncationSignal} 判定，
 * 随原始文本一起交给输出流水线；三个信号缺省为 {@code false}（无从判定）。
 *
 * @param text             模型原始文本（含 Markdown 围栏等）
 * @param truncated        达到输出长度上限（{@code finish_reason=length}）
 * @param refused          模型拒答
 * @param contentFiltered  供应商内容过滤（{@code finish_reason=content_filter}）
 */
public record RawModelOutput(String text, boolean truncated, boolean refused, boolean contentFiltered) {

    public static RawModelOutput of(String text) {
        return new RawModelOutput(text, false, false, false);
    }
}

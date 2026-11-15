package com.tioka.model.application.health;

/**
 * MH-10：一次模型调用的统计样本（写入滚动窗口的输入）。
 *
 * @param providerId       供应商 ID（可空）
 * @param bindingId        绑定 ID（可空）
 * @param aliasId          别名 ID（可空）
 * @param latencyMs        端到端延迟（毫秒）
 * @param ttftMs           首次 token 延迟（毫秒；非流式或不可得时为 null）
 * @param tokensIn         输入 token
 * @param tokensOut        输出 token
 * @param tokensCached     缓存命中 token
 * @param costMicros       成本（微元）
 * @param errorClass       错误类别：{@code null}（成功）/ {@code 429} / {@code 5xx} / {@code timeout} / {@code auth} / {@code other}
 * @param lastErrorMessage 最近一次错误消息（截断后；成功时为 null）
 * @param traceId          链路 traceId（可空；仅失败样本记录）
 */
public record ModelCallSample(String providerId,
                              String bindingId,
                              String aliasId,
                              long latencyMs,
                              Long ttftMs,
                              int tokensIn,
                              int tokensOut,
                              int tokensCached,
                              long costMicros,
                              String errorClass,
                              String lastErrorMessage,
                              String traceId) {

    /** 是否成功调用。 */
    public boolean success() {
        return errorClass == null || errorClass.isBlank();
    }

    /** 便捷构造：无别名、无缓存、无 traceId 的样本。 */
    public static ModelCallSample of(String providerId, String bindingId, long latencyMs,
                                     int tokensIn, int tokensOut, long costMicros, String errorClass) {
        return new ModelCallSample(providerId, bindingId, null, latencyMs, null,
                tokensIn, tokensOut, 0, costMicros, errorClass, null, null);
    }
}

package com.tioka.model.application.health;

/**
 * MH-10（ADR-041 第 5.1 节）：单个作用对象（供应商 / 绑定 / 别名）的运行健康快照。
 *
 * <p>健康态取 {@link ModelHealthEvaluator} 六态结论；其余为最近 60 分钟滚动窗口
 * （速率 / 错误率 / 延迟分位 / 首 token 分位 / token / 成本）与熔断、配额、最近错误样本。
 *
 * @param dim                    维度：{@code provider} / {@code binding} / {@code alias}
 * @param id                     作用对象标识
 * @param state                  六态健康结论
 * @param calls60m               近 60 分钟调用数
 * @param callsPerMin            近 60 分钟平均每分钟调用数（速率）
 * @param errorRate60m           近 60 分钟错误率
 * @param ratio429_60m           近 60 分钟 429 占比
 * @param p50LatencyMs           近 60 分钟 P50 延迟（毫秒）
 * @param p95LatencyMs           近 60 分钟 P95 延迟（毫秒）
 * @param p95TtftMs              近 60 分钟 P95 首 token 延迟（毫秒）
 * @param tokensIn               近 60 分钟输入 token 数
 * @param tokensOut              近 60 分钟输出 token 数
 * @param costMicros             近 60 分钟成本（微元）
 * @param circuitOpen            是否熔断（MH-03 冷却中）
 * @param probeConsecutiveFailures 主动探测连续失败次数
 * @param quotaTokenRatio        供应商 token 月度配额已用比例；无上限/不适用为 null
 * @param quotaCostRatio         供应商成本月度配额已用比例；无上限/不适用为 null
 * @param lastErrorClass         最近一次错误类别；无样本为 null
 * @param lastErrorMessage       最近一次错误消息；无样本为 null
 * @param lastErrorEpochMs       最近一次错误时间（毫秒）；无样本为 0
 */
public record ModelHealthSnapshot(String dim,
                                  String id,
                                  String state,
                                  long calls60m,
                                  double callsPerMin,
                                  double errorRate60m,
                                  double ratio429_60m,
                                  double p50LatencyMs,
                                  double p95LatencyMs,
                                  double p95TtftMs,
                                  long tokensIn,
                                  long tokensOut,
                                  long costMicros,
                                  boolean circuitOpen,
                                  int probeConsecutiveFailures,
                                  Double quotaTokenRatio,
                                  Double quotaCostRatio,
                                  String lastErrorClass,
                                  String lastErrorMessage,
                                  long lastErrorEpochMs) {
}

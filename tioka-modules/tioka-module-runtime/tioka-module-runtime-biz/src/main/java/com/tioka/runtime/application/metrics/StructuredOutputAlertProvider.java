package com.tioka.runtime.application.metrics;

import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertSignalProvider;
import com.tioka.runtime.api.metrics.StructuredOutputStatsApi;
import com.tioka.runtime.api.metrics.StructuredOutputStatsApi.Bucket;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SCH-10（ADR-066 §10.1）：结构化输出指标告警信号源（GOV-13 {@link AlertSignalProvider} 实现）。
 *
 * <p>承载数据在 runtime 模块，故信号源随运行指标一并置于本模块；治理模块只做规则求值、去重与通知。
 * 按 §10.1 缺省口径对监控对象 {@code ALL} 逐「（Schema、模型）」对象展开两类观测：
 * <ul>
 *   <li>{@code STRUCTURED_FIRST_PASS_DROP}：本窗口首次通过率相对上一等长窗口的降幅（百分点）；</li>
 *   <li>{@code STRUCTURED_FAILURE_RATE}：本窗口失败率（比例 0〜1）。</li>
 * </ul>
 *
 * <p>窗口长度取规则 {@code window_minutes}（缺省 7 天）；聚合按（{@code schemaRef}、模型）合并生成方式维度，
 * 经 {@link StructuredOutputStatsApi} 直接读投影列、不解析 {@code content_json}；无数据不产生观测。
 * 观测 {@code targetId} 取「{@code schemaRef}|{@code modelRef}」，供 {@code ALL} 规则按对象独立去重。
 */
@Component
public class StructuredOutputAlertProvider implements AlertSignalProvider {

    /** ADR-066 §10.1 缺省窗口：7 天。 */
    private static final int DEFAULT_WINDOW_MINUTES = 7 * 24 * 60;

    private final ObjectProvider<StructuredOutputStatsApi> stats;

    public StructuredOutputAlertProvider(ObjectProvider<StructuredOutputStatsApi> stats) {
        this.stats = stats;
    }

    @Override
    public boolean supports(AlertConditionType condition) {
        return condition == AlertConditionType.STRUCTURED_FIRST_PASS_DROP
                || condition == AlertConditionType.STRUCTURED_FAILURE_RATE;
    }

    @Override
    public List<AlertObservation> observe(AlertRuleRef rule, Instant now) {
        StructuredOutputStatsApi api = stats == null ? null : stats.getIfAvailable();
        if (api == null || rule == null || now == null) {
            return List.of();
        }
        int windowMinutes = rule.windowMinutes() > 0 ? rule.windowMinutes() : DEFAULT_WINDOW_MINUTES;
        Instant from = now.minus(windowMinutes, ChronoUnit.MINUTES);
        Map<String, Totals> current = merge(api.aggregate(from, now));

        if (rule.conditionType() == AlertConditionType.STRUCTURED_FAILURE_RATE) {
            return failureRateObservations(current, now);
        }
        Instant previousFrom = from.minus(windowMinutes, ChronoUnit.MINUTES);
        Map<String, Totals> previous = merge(api.aggregate(previousFrom, from));
        return dropObservations(current, previous, now);
    }

    private List<AlertObservation> failureRateObservations(Map<String, Totals> current, Instant now) {
        List<AlertObservation> out = new ArrayList<>();
        for (Map.Entry<String, Totals> entry : current.entrySet()) {
            Totals t = entry.getValue();
            if (t.total <= 0L) {
                continue;
            }
            double rate = t.rate(t.failed);
            out.add(AlertObservation.ofValue(entry.getKey(), rate,
                    String.format("失败率 %.2f%%（%d/%d）", rate * 100d, t.failed, t.total), now));
        }
        return out;
    }

    private List<AlertObservation> dropObservations(Map<String, Totals> current,
                                                    Map<String, Totals> previous,
                                                    Instant now) {
        List<AlertObservation> out = new ArrayList<>();
        for (Map.Entry<String, Totals> entry : current.entrySet()) {
            Totals cur = entry.getValue();
            Totals prev = previous.get(entry.getKey());
            if (cur.total <= 0L || prev == null || prev.total <= 0L) {
                continue;
            }
            double dropPoints = (prev.rate(prev.firstPass) - cur.rate(cur.firstPass)) * 100d;
            out.add(AlertObservation.ofValue(entry.getKey(), dropPoints,
                    String.format("首次通过率由 %.2f%% 降至 %.2f%%，降幅 %.2f 个百分点",
                            prev.rate(prev.firstPass) * 100d, cur.rate(cur.firstPass) * 100d, dropPoints),
                    now));
        }
        return out;
    }

    /** 按（schemaRef, 模型）合并生成方式维度，形成「（Schema、模型）」级计数。 */
    private static Map<String, Totals> merge(List<Bucket> buckets) {
        Map<String, Totals> out = new LinkedHashMap<>();
        if (buckets == null) {
            return out;
        }
        for (Bucket bucket : buckets) {
            out.computeIfAbsent(targetId(bucket.schemaRef(), bucket.modelRef()),
                    k -> new Totals()).add(bucket);
        }
        return out;
    }

    private static String targetId(String schemaRef, String modelRef) {
        return (schemaRef == null ? "?" : schemaRef) + '|' + (modelRef == null ? "?" : modelRef);
    }

    /** 一个（Schema、模型）对象的结局计数汇总（跨生成方式）。 */
    private static final class Totals {
        private long total;
        private long firstPass;
        private long failed;

        private void add(Bucket bucket) {
            total += bucket.total();
            firstPass += bucket.firstPass();
            failed += bucket.failed();
        }

        private double rate(long n) {
            return total <= 0L ? 0d : (double) n / (double) total;
        }
    }
}

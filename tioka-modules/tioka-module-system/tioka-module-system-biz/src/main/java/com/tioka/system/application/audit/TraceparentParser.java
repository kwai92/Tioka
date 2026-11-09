package com.tioka.system.application.audit;

import java.util.regex.Pattern;

/**
 * W3C traceparent 手写解析（P2-L6，OBS-01）：格式 {@code 00-{32hex}-{16hex}-{2hex}}。
 *
 * <p>不引入 OTel SDK（约束：无新运行时依赖）；OTel API 的
 * {@code W3CTraceContextPropagator} 仅供 MQ 消费侧（infra-biz）复用，
 * HTTP 入口在本模块用手写解析保持轻量。
 *
 * <p>规则（W3C Trace Context）：版本 00 严格校验长度；trace-id / span-id
 * 全零非法；小写十六进制。
 */
public final class TraceparentParser {

    private static final Pattern FORMAT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceparentParser() {}

    /** 解析结果：trace-id 与 parent-span-id（flags 不参与审计语义，不返回）。 */
    public record Parsed(String traceId, String spanId) {}

    /**
     * 合法返回 {@link Parsed}；null / 空白 / 格式非法 / 全零 id 返回 {@code null}。
     */
    public static Parsed parse(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String v = traceparent.trim();
        if (v.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = FORMAT.matcher(v);
        if (!m.matches()) {
            return null;
        }
        String traceId = m.group(1);
        String spanId = m.group(2);
        if (isAllZero(traceId) || isAllZero(spanId)) {
            return null;
        }
        return new Parsed(traceId, spanId);
    }

    private static boolean isAllZero(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}

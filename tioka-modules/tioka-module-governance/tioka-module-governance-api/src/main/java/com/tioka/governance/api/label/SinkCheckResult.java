package com.tioka.governance.api.label;

/**
 * 去向许可结果（M3-02 SinkGuard）。
 */
public record SinkCheckResult(
        SinkVerdict verdict,
        Object redactedPayload,
        String reason
) {
    public enum SinkVerdict {
        PASS,
        REDACTED,
        DENY,
        REQUIRE_APPROVAL
    }

    public static SinkCheckResult pass() {
        return new SinkCheckResult(SinkVerdict.PASS, null, null);
    }

    public static SinkCheckResult deny(String reason) {
        return new SinkCheckResult(SinkVerdict.DENY, null, reason);
    }
}

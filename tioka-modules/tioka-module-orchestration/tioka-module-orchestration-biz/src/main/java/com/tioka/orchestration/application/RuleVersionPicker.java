package com.tioka.orchestration.application;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * 稳定 / 灰度版本选择：同一 subjectKey 总落同一桶。
 */
public final class RuleVersionPicker {

    private RuleVersionPicker() {}

    public record Pick(String versionId, boolean canary) {}

    public static Pick pick(String stableVersionId, String canaryVersionId, Integer canaryPercent,
                            String subjectKey) {
        if (canaryVersionId != null && !canaryVersionId.isBlank()
                && canaryPercent != null && canaryPercent > 0
                && Math.floorMod(murmur3_32(subjectKey == null ? "" : subjectKey), 100) < canaryPercent) {
            return new Pick(canaryVersionId, true);
        }
        return new Pick(stableVersionId, false);
    }

    static int murmur3_32(String subjectKey) {
        return Hashing.murmur3_32_fixed()
                .hashString(subjectKey, StandardCharsets.UTF_8)
                .asInt();
    }
}

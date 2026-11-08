package com.tioka.runtime.api.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预演变更单与相关类型（ADR-033 / TRU-05）。实现在 PK-24b。
 */
public record ChangeSet(
        String summary,
        List<Change> changes,
        Totals totals,
        String fingerprint,
        boolean estimated,
        Instant validUntil) {

    public ChangeSet {
        if (changes == null) {
            changes = List.of();
        }
    }

    public record Change(
            String objectType,
            String objectId,
            ChangeOp op,
            Map<String, Object> before,
            Map<String, Object> after) {
        public Change {
            if (before == null) {
                before = Map.of();
            }
            if (after == null) {
                after = Map.of();
            }
        }
    }

    public record Totals(int records, Long amountMicros, String currency, Set<String> objectTypes) {
        public Totals {
            if (objectTypes == null) {
                objectTypes = Set.of();
            }
        }
    }

    public record ApplyReceipt(String externalRef, Map<String, Object> compensationToken, Instant appliedAt) {
        public ApplyReceipt {
            if (compensationToken == null) {
                compensationToken = Map.of();
            }
        }
    }

    public record CompensationResult(boolean ok, String message, String externalRef) {}
}

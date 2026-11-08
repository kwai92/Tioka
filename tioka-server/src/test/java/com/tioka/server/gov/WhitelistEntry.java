package com.tioka.server.gov;

/**
 * 带类别与原因的审计白名单条目（OBS-02 / ADR-072）。
 */
record WhitelistEntry(String pattern, WhitelistKind kind, String reason) {
    WhitelistEntry {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason required for " + pattern);
        }
    }
}

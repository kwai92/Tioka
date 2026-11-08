package com.tioka.orchestration.api.engine;

/**
 * Mandate 来由（ADR-020 修订第 3、8 条；表 {@code orch_mandate.via}）。
 */
public enum MandateVia {
    ADDRESSED,
    FOLLOWUP,
    ADMISSION,
    BOT
}

package com.tioka.runtime.api.kernel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Turn 状态机契约（ADR-016、ADR-029 第 16 条）。实现必须遵守同一张转换表。 */
class TurnStatusContractTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            "QUEUED, RUNNING, true",
            "QUEUED, CANCELLED, true",
            "QUEUED, COMPLETED, false",
            "RUNNING, WAITING_APPROVAL, true",
            "RUNNING, WAITING_CHILD, true",
            "RUNNING, INTERRUPTED, true",
            "RUNNING, COMPLETED, true",
            "RUNNING, FAILED, true",
            "RUNNING, CANCELLED, true",
            "RUNNING, QUEUED, false",
            "WAITING_APPROVAL, QUEUED, true",
            "WAITING_APPROVAL, RUNNING, false",
            "WAITING_CHILD, QUEUED, true",
            "WAITING_CHILD, CANCELLED, true",
            "INTERRUPTED, QUEUED, true",
            "INTERRUPTED, RUNNING, false",
            "COMPLETED, QUEUED, false",
            "FAILED, RUNNING, false",
            "CANCELLED, QUEUED, false"
    })
    void transitions(TurnStatus from, TurnStatus to, boolean allowed) {
        assertEquals(allowed, from.canTransitionTo(to));
    }

    @Test
    void terminalStatesHaveNoSuccessor() {
        for (TurnStatus s : TurnStatus.values()) {
            assertEquals(s.isTerminal(), s.allowedNext().isEmpty(), s.name());
        }
    }

    @Test
    void onlyRunningHoldsLease() {
        for (TurnStatus s : TurnStatus.values()) {
            assertEquals(s == TurnStatus.RUNNING, s.holdsLease(), s.name());
        }
    }
}

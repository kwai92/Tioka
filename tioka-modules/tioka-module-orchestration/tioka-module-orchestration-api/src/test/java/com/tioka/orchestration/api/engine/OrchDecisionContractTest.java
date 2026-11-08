package com.tioka.orchestration.api.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 决定与归单枚举 ↔ V132 / ADR-020 约束（PK-12a / ORC-03）。
 */
class OrchDecisionContractTest {

    @Test
    void decisionCodesMatchDbCheck() {
        assertEquals(
                Set.of("ACCEPT", "IGNORE", "STOP", "UNAVAILABLE", "WOULD_ACCEPT"),
                names(DecisionCode.values()));
    }

    @Test
    void mandateViaMatchDbCheck() {
        assertEquals(
                Set.of("ADDRESSED", "FOLLOWUP", "ADMISSION", "BOT"),
                names(MandateVia.values()));
    }

    @Test
    void concurrencyWireIsLowercase() {
        assertEquals("serial", ConcurrencyMode.SERIAL.wire());
        assertEquals("merge", ConcurrencyMode.MERGE.wire());
        assertEquals("parallel", ConcurrencyMode.PARALLEL.wire());
        assertEquals(ConcurrencyMode.SERIAL, ConcurrencyMode.fromWire("serial"));
    }

    @Test
    void acceptModeWireIsLowercase() {
        assertEquals(Set.of("off", "observe", "auto"),
                Arrays.stream(AcceptMode.values()).map(AcceptMode::wire).collect(Collectors.toSet()));
    }

    @Test
    void eventAckFactories() {
        assertEquals(OrchApi.Disposition.STORED, OrchApi.EventAck.stored("evt1").disposition());
        assertEquals(OrchApi.Disposition.DUPLICATE, OrchApi.EventAck.duplicate("evt1").disposition());
        assertEquals(OrchApi.Disposition.FILTERED, OrchApi.EventAck.filtered("evt1").disposition());
    }

    @Test
    void assignResultCarriesWorkItemAndOptionalRun() {
        AssignResult queued = new AssignResult(
                "wi1", "man1", "run1", true, true, true, ConcurrencyMode.SERIAL);
        assertTrue(queued.queued());
        assertEquals("run1", queued.runId());

        AssignResult mergeOnly = new AssignResult(
                "wi1", "man1", null, false, false, false, ConcurrencyMode.MERGE);
        assertNull(mergeOnly.runId());
        assertFalse(mergeOnly.createdRun());
    }

    @Test
    void decisionViewFields() {
        DecisionView view = new DecisionView(
                "dec1", "evt1", "rv1", OrchApi.Strength.EXPLICIT,
                DecisionCode.ACCEPT, null, "wi1");
        assertEquals(DecisionCode.ACCEPT, view.decision());
        assertEquals(OrchApi.Strength.EXPLICIT, view.strength());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}

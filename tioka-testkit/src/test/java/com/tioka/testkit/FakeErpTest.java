package com.tioka.testkit;

import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.StaleChangeSetException;
import com.tioka.runtime.api.spi.ToolInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FakeErpTest {

    private FakeErp erp;

    @BeforeEach
    void setUp() {
        erp = new FakeErp()
                .seedOrder("ord_1", "OPEN", 1_000_000L)
                .seedAr("ar_1", "OPEN", 2_500_000L);
    }

    @Test
    void previewDoesNotMutate() {
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_UPDATE_ORDER,
                Map.of("orderId", "ord_1", "status", "CLOSED"));
        ChangeSet a = erp.preview(inv);
        ChangeSet b = erp.preview(inv);
        assertEquals("OPEN", erp.order("ord_1").get("status"));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertFalse(a.estimated());
    }

    @Test
    void applyIsIdempotent() {
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_UPDATE_ORDER,
                Map.of("orderId", "ord_1", "status", "CLOSED"));
        ChangeSet cs = erp.preview(inv);
        ChangeSet.ApplyReceipt r1 = erp.apply(inv, cs, "item_1");
        ChangeSet.ApplyReceipt r2 = erp.apply(inv, cs, "item_1");
        assertEquals(r1.externalRef(), r2.externalRef());
        assertEquals("CLOSED", erp.order("ord_1").get("status"));
    }

    @Test
    void staleFingerprint() {
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_WRITEOFF_AR, Map.of("arId", "ar_1"));
        ChangeSet cs = erp.preview(inv);
        erp.mutateBefore("ar", "ar_1", "status", "PARTIAL");
        StaleChangeSetException ex = assertThrows(StaleChangeSetException.class,
                () -> erp.apply(inv, cs, "item_stale"));
        assertTrue(ex.getMessage().contains("数据已变化"));
        assertEquals("PARTIAL", erp.ar("ar_1").get("status"));
    }

    @Test
    void compensateRestores() {
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_UPDATE_ORDER,
                Map.of("orderId", "ord_1", "status", "CLOSED"));
        ChangeSet cs = erp.preview(inv);
        ChangeSet.ApplyReceipt receipt = erp.apply(inv, cs, "item_c");
        assertEquals("CLOSED", erp.order("ord_1").get("status"));
        ChangeSet.CompensationResult ok = erp.compensate(receipt, "item_c:undo");
        assertTrue(ok.ok());
        assertEquals("OPEN", erp.order("ord_1").get("status"));
        ChangeSet.CompensationResult again = erp.compensate(receipt, "item_c:undo");
        assertTrue(again.ok());
    }

    @Test
    void compensateFailureInjected() {
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_WRITEOFF_AR, Map.of("arId", "ar_1"));
        ChangeSet cs = erp.preview(inv);
        ChangeSet.ApplyReceipt receipt = erp.apply(inv, cs, "item_f");
        erp.setCompensateFail(true);
        ChangeSet.CompensationResult r = erp.compensate(receipt, "item_f:undo");
        assertFalse(r.ok());
        assertEquals("WRITTEN_OFF", erp.ar("ar_1").get("status"));
    }
}

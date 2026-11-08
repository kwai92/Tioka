package com.tioka.server.gate.support.erp;

import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.StaleChangeSetException;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.testkit.FakeErp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ENG-04d：FakeErp 多步场景（5 步写操作 + 补偿失败 / before 被他人修改）。
 * <p>供 G3 冒烟与后续 Undo/自治闸门复用；不改 FakeErp 本体。
 */
public final class FakeErpMultiStepScenarios {

    public record StepReceipt(String arId, String idempotencyKey, ChangeSet.ApplyReceipt receipt,
                              Map<String, Object> beforeStatus) {}

    public record FiveStepResult(FakeErp erp, List<StepReceipt> steps,
                                 Map<String, Object> snapshotBefore) {}

    private FakeErpMultiStepScenarios() {}

    /** 种子 5 张应收，执行 5 次核销，返回执行前快照与回执（倒序补偿用）。 */
    public static FiveStepResult runFiveWriteoffs() {
        FakeErp erp = new FakeErp();
        List<String> ids = List.of("ar_s1", "ar_s2", "ar_s3", "ar_s4", "ar_s5");
        for (int i = 0; i < ids.size(); i++) {
            erp.seedAr(ids.get(i), "OPEN", 1_000_000L * (i + 1));
        }
        Map<String, Object> snapshot = snapshotArs(erp, ids);
        List<StepReceipt> steps = new ArrayList<>();
        for (String id : ids) {
            ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_WRITEOFF_AR, Map.of("arId", id));
            ChangeSet cs = erp.preview(inv);
            String key = "step-" + id;
            ChangeSet.ApplyReceipt receipt = erp.apply(inv, cs, key);
            steps.add(new StepReceipt(id, key, receipt, Map.of("status", "OPEN")));
        }
        for (String id : ids) {
            if (!"WRITTEN_OFF".equals(erp.ar(id).get("status"))) {
                throw new IllegalStateException("expected WRITTEN_OFF for " + id);
            }
        }
        return new FiveStepResult(erp, List.copyOf(steps), snapshot);
    }

    /** 5 步写完后倒序补偿，状态应回到执行前快照。 */
    public static void compensateAllReverse(FiveStepResult result) {
        List<StepReceipt> steps = result.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            StepReceipt s = steps.get(i);
            ChangeSet.CompensationResult r = result.erp().compensate(s.receipt(), s.idempotencyKey() + ":undo");
            if (!r.ok()) {
                throw new IllegalStateException("compensate failed at " + s.arId() + ": " + r.message());
            }
        }
        assertSnapshot(result.erp(), result.snapshotBefore());
    }

    /**
     * 补偿失败组合：前两步可补偿，第 3 步注入失败后停止；后两步保持已核销。
     *
     * @return 失败的应收 id
     */
    public static String compensateFailureStopsAtThird() {
        FiveStepResult result = runFiveWriteoffs();
        FakeErp erp = result.erp();
        List<StepReceipt> steps = result.steps();
        // 先成功补偿最后两步（倒序的前两笔）
        for (int i = steps.size() - 1; i >= 3; i--) {
            StepReceipt s = steps.get(i);
            ChangeSet.CompensationResult r = erp.compensate(s.receipt(), s.idempotencyKey() + ":undo");
            if (!r.ok()) {
                throw new IllegalStateException("unexpected fail at " + s.arId());
            }
        }
        erp.setCompensateFail(true);
        StepReceipt third = steps.get(2);
        ChangeSet.CompensationResult failed = erp.compensate(third.receipt(), third.idempotencyKey() + ":undo");
        if (failed.ok()) {
            throw new IllegalStateException("expected compensate failure at " + third.arId());
        }
        // 后两步已恢复；第 3〜1 步仍为 WRITTEN_OFF
        if (!"OPEN".equals(erp.ar(steps.get(4).arId()).get("status"))
                || !"OPEN".equals(erp.ar(steps.get(3).arId()).get("status"))) {
            throw new IllegalStateException("steps 5/4 should be restored");
        }
        for (int i = 0; i <= 2; i++) {
            if (!"WRITTEN_OFF".equals(erp.ar(steps.get(i).arId()).get("status"))) {
                throw new IllegalStateException("step " + (i + 1) + " should remain written off");
            }
        }
        return third.arId();
    }

    /** before 被他人修改：预演后 mutate，apply 抛 StaleChangeSetException，ERP 不变。 */
    public static void beforeMutatedRejectsApply() {
        FakeErp erp = new FakeErp().seedAr("ar_drift", "OPEN", 9_000_000L);
        ToolInvocation inv = new ToolInvocation(FakeErp.TOOL_WRITEOFF_AR, Map.of("arId", "ar_drift"));
        ChangeSet cs = erp.preview(inv);
        erp.mutateBefore("ar", "ar_drift", "status", "PARTIAL");
        try {
            erp.apply(inv, cs, "drift-key");
            throw new IllegalStateException("expected StaleChangeSetException");
        } catch (StaleChangeSetException expected) {
            if (!"PARTIAL".equals(erp.ar("ar_drift").get("status"))) {
                throw new IllegalStateException("ERP should keep mutated before");
            }
        }
    }

    private static Map<String, Object> snapshotArs(FakeErp erp, List<String> ids) {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String id : ids) {
            snap.put(id, Map.copyOf(erp.ar(id)));
        }
        return Map.copyOf(snap);
    }

    @SuppressWarnings("unchecked")
    private static void assertSnapshot(FakeErp erp, Map<String, Object> snapshot) {
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            Map<String, Object> expected = (Map<String, Object>) e.getValue();
            Map<String, Object> actual = erp.ar(e.getKey());
            if (!expected.equals(actual)) {
                throw new IllegalStateException("snapshot mismatch for " + e.getKey()
                        + " expected=" + expected + " actual=" + actual);
            }
        }
    }
}

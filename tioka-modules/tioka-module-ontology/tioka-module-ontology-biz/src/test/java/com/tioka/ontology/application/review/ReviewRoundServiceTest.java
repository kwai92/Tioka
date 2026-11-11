package com.tioka.ontology.application.review;

import com.tioka.ontology.infrastructure.LeaseReviewRoundRepository;
import com.tioka.ontology.infrastructure.persistence.LeaseReviewRoundDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * LSE-06 局部重审判定（M3-04）：diff 路径 ∩ modelRefs.path 或 requires → 该要点重跑。
 * 路径相交口径：精确相等或前缀（. 与 [ 处截断）；索引级差异不视为相交。
 */
class ReviewRoundServiceTest {

    private final LeaseReviewRoundRepository rounds = mock(LeaseReviewRoundRepository.class);
    private final ReviewRoundService service = new ReviewRoundService(rounds);

    private static Map<String, Object> finding(String code, String... refPaths) {
        List<Map<String, Object>> refs = List.of(refPaths).stream()
                .map(p -> Map.<String, Object>of("path", (Object) p))
                .toList();
        return Map.of("checkpointCode", code, "modelRefs", refs);
    }

    @Test
    void modelRefs路径精确相交() {
        Set<String> affected = service.affectedCheckpoints(
                List.of("m.subjects.S1.rentStartDate"),
                List.of(finding("D-003", "m.subjects.S1.rentStartDate"),
                        finding("D-009", "m.subjects.S1.area")),
                Map.of());
        assertEquals(Set.of("D-003"), affected);
    }

    @Test
    void requires路径相交() {
        Set<String> affected = service.affectedCheckpoints(
                List.of("m.contracts[0].signDate"),
                List.of(),
                Map.of("D-067", List.of("m.contracts[0].signDate"),
                        "D-068", List.of("m.contracts[0].effectiveDate")));
        assertEquals(Set.of("D-067"), affected);
    }

    @Test
    void 前缀路径相交_双向() {
        // diff 更深：diff m.feeItems.RENT.calc.baseAmount.value 命中 ref m.feeItems.RENT.calc.baseAmount
        // diff 更浅：diff m.feeItems.RENT 覆盖 ref m.feeItems.RENT.calc.baseAmount
        Set<String> affected = service.affectedCheckpoints(
                List.of("m.feeItems.RENT.calc.baseAmount.value", "m.feeItems.RENT.pay"),
                List.of(finding("D-001", "m.feeItems.RENT.calc.baseAmount"),
                        finding("D-005", "m.feeItems.MGMT.pay.payCycle")),
                Map.of());
        assertEquals(Set.of("D-001"), affected);
    }

    @Test
    void 索引级差异不相交() {
        Set<String> affected = service.affectedCheckpoints(
                List.of("m.contracts[1].signDate"),
                List.of(finding("D-067", "m.contracts[0].signDate")),
                Map.of());
        assertTrue(affected.isEmpty(), "[0] 与 [1] 不相交");
    }

    @Test
    void 无diff或无上一轮结论则不重跑() {
        assertTrue(service.affectedCheckpoints(List.of(), List.of(finding("D-001", "m.a")), Map.of()).isEmpty());
        assertTrue(service.affectedCheckpoints(null, List.of(finding("D-001", "m.a")), Map.of()).isEmpty());
        assertTrue(service.affectedCheckpoints(List.of("m.a"), List.of(), Map.of()).isEmpty());
    }

    @Test
    void 同一要点多引用其一命中即重跑() {
        Set<String> affected = service.affectedCheckpoints(
                List.of("m.subjects.S1.areaBasis"),
                List.of(finding("D-010", "m.subjects.S1.area", "m.subjects.S1.areaBasis")),
                Map.of());
        assertEquals(Set.of("D-010"), affected);
    }

    @Test
    void 轮次与记录委托仓储() {
        org.mockito.Mockito.when(rounds.currentRound("rr-1")).thenReturn(2);
        assertEquals(3, service.nextRound("rr-1"));

        LeaseReviewRoundDO row = new LeaseReviewRoundDO();
        row.setReviewRunId("rr-1");
        row.setRound(3);
        service.recordRound(row);
        verify(rounds).upsert(row);
    }
}

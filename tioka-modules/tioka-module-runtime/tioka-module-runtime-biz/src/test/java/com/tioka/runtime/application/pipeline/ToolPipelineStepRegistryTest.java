package com.tioka.runtime.application.pipeline;

import com.tioka.framework.error.BizException;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ToolPipelineStepRegistryTest {

    @Test
    void emptyRegistryContinues() {
        ToolPipelineStepRegistry reg = new ToolPipelineStepRegistry(List.of());
        assertTrue(reg.isEmpty());
        ToolPipelineVerdict v = reg.run(ctx());
        assertEquals(ToolPipelineVerdict.CONTINUE, v);
    }

    @Test
    void stepsRunInOrder() {
        List<Double> seen = new ArrayList<>();
        ToolPipelineStep a = step(2.5, "a", ToolPipelineVerdict.CONTINUE, seen);
        ToolPipelineStep b = step(7.0, "b", ToolPipelineVerdict.CONTINUE, seen);
        ToolPipelineStepRegistry reg = new ToolPipelineStepRegistry(List.of(b, a));
        assertEquals(ToolPipelineVerdict.CONTINUE, reg.run(ctx()));
        assertEquals(List.of(2.5, 7.0), seen);
    }

    @Test
    void denyStopsAndThrows() {
        ToolPipelineStep deny = step(7.0, "charter", ToolPipelineVerdict.DENY, new ArrayList<>());
        ToolPipelineStepRegistry reg = new ToolPipelineStepRegistry(List.of(deny));
        assertThrows(BizException.class, () -> reg.run(ctx()));
    }

    @Test
    void duplicateOrderRejected() {
        ToolPipelineStep a = step(7.0, "a", ToolPipelineVerdict.CONTINUE, new ArrayList<>());
        ToolPipelineStep b = step(7.0, "b", ToolPipelineVerdict.CONTINUE, new ArrayList<>());
        assertThrows(IllegalStateException.class, () -> new ToolPipelineStepRegistry(List.of(a, b)));
    }

    private static ToolPipelineContext ctx() {
        Principal p = new Principal("1", "ws", PrincipalType.USER, Set.of(), Set.of("*"), "password");
        return new ToolPipelineContext(
                ToolCallContexts.forTurn(p, "t", "u", "av", "chat", List.of(), Map.of()),
                "echo", Map.of());
    }

    private static ToolPipelineStep step(double order, String name, ToolPipelineVerdict verdict,
                                         List<Double> seen) {
        return new ToolPipelineStep() {
            @Override public double order() { return order; }
            @Override public String name() { return name; }
            @Override public ToolPipelineVerdict execute(ToolPipelineContext ctx) {
                seen.add(order);
                return verdict;
            }
        };
    }
}

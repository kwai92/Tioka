package com.tioka.runtime.application.pipeline;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具管线步骤注册表（P3-00b / K1b）。
 * 无步骤 bean 时 {@link #run} 恒为 CONTINUE，DefaultToolGateway 保持现状路径。
 */
@Component
public class ToolPipelineStepRegistry {

    private final List<ToolPipelineStep> steps;

    public ToolPipelineStepRegistry(List<ToolPipelineStep> steps) {
        List<ToolPipelineStep> sorted = new ArrayList<>(steps == null ? List.of() : steps);
        sorted.sort(Comparator.comparingDouble(ToolPipelineStep::order));
        Set<Double> seen = new HashSet<>();
        for (ToolPipelineStep step : sorted) {
            if (!seen.add(step.order())) {
                throw new IllegalStateException(
                        "重复的管线序号 " + step.order() + "（步骤 " + step.name() + "）");
            }
        }
        this.steps = List.copyOf(sorted);
    }

    public List<ToolPipelineStep> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * 按序号依次执行。DENY → FORBIDDEN；ESCALATE 写入上下文属性后返回，由网关转审批。
     */
    public ToolPipelineVerdict run(ToolPipelineContext ctx) {
        for (ToolPipelineStep step : steps) {
            ToolPipelineVerdict v = step.execute(ctx);
            if (v == null || v == ToolPipelineVerdict.CONTINUE) {
                continue;
            }
            if (v == ToolPipelineVerdict.DENY) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "管线步骤拒绝: " + step.name());
            }
            ctx.put("pipeline.verdict", v);
            ctx.put("pipeline.step", step.name());
            return v;
        }
        return ToolPipelineVerdict.CONTINUE;
    }
}

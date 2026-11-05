package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OutputNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "Output"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        ctx.markTerminal(input);
        return input == null ? Map.of() : input;
    }
}

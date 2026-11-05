package com.tioka.flow.application.exec;

import java.util.Map;

public interface NodeExecutor {
    String type();

    Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception;
}

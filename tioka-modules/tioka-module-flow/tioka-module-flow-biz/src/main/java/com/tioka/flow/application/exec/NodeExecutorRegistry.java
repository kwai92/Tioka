package com.tioka.flow.application.exec;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class NodeExecutorRegistry {
    private final Map<String, NodeExecutor> byType = new HashMap<>();

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        for (NodeExecutor e : executors) {
            byType.put(e.type(), e);
        }
    }

    public NodeExecutor require(String type) {
        NodeExecutor e = byType.get(type);
        if (e == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知节点类型: " + type);
        }
        return e;
    }

    public boolean supports(String type) {
        return byType.containsKey(type);
    }
}

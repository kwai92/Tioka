package com.tioka.runtime.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultToolGateway implements ToolGateway {
    private static final Logger log = LoggerFactory.getLogger(DefaultToolGateway.class);
    private final ToolRegistry registry;

    public DefaultToolGateway(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ToolProvider.ToolDescriptor> listTools(SessionToolContext ctx) {
        if (ctx == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            return List.of();
        }
        return registry.listAll(ctx.agentVersionId());
    }

    @Override
    public ToolProvider.ToolResult call(SessionToolContext ctx, String toolName, Map<String, Object> args) {
        if (ctx == null || ctx.agentVersionId() == null || ctx.agentVersionId().isBlank()) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少 agentVersionId，拒绝工具调用");
        }
        String agentVersionId = ctx.agentVersionId();
        boolean allowed = listTools(ctx).stream().anyMatch(t -> t.name().equals(toolName));
        if (!allowed) {
            log.warn("tool_denied session={} agentVersion={} tool={}",
                    ctx.sessionId(), agentVersionId, toolName);
            throw new BizException(ErrorCode.FORBIDDEN, "工具未授权: " + toolName);
        }
        ToolProvider.ToolResult result = registry.invoke(agentVersionId, toolName, args);
        log.info("tool_call session={} tool={} ok={}", ctx.sessionId(), toolName, result.ok());
        return result;
    }

    @Override
    public List<Map<String, Object>> asOpenAiTools(SessionToolContext ctx) {
        return listTools(ctx).stream().map(d -> {
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.put("parameters", d.inputSchema());
            return Map.<String, Object>of("type", "function", "function", fn);
        }).toList();
    }
}

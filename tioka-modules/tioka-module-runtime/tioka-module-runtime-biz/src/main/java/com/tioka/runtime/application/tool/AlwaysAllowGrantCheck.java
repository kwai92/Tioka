package com.tioka.runtime.application.tool;

import com.tioka.runtime.api.spi.GrantCheck;
import com.tioka.runtime.api.spi.ToolGateway;
import org.springframework.stereotype.Component;

import java.util.Map;

/** M1 第 2.5 步占位：恒放行（ADR-029 第 46 条；M3 MAS-01 替换）。 */
@Component
public class AlwaysAllowGrantCheck implements GrantCheck {
    @Override
    public String check(ToolGateway.ToolCallContext ctx, String toolName, Map<String, Object> args) {
        return null;
    }
}

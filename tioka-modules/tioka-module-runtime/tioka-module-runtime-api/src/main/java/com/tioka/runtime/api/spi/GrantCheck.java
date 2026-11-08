package com.tioka.runtime.api.spi;

import java.util.Map;

/**
 * 工具调用管线第 2.5 步「授予」检查（ADR-029 第 46 条；M1 恒放行，M3 MAS-01 替换）。
 *
 * @return {@code null} 表示放行；非空为拒绝原因
 */
public interface GrantCheck {
    String check(ToolGateway.ToolCallContext ctx, String toolName, Map<String, Object> args);
}

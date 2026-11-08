package com.tioka.ontology.application.pack;

import java.util.Map;

/**
 * 确认后投影钩子（如写回业务系统）。纯函数约束由 {@link PureFunctionExecutor} 执行。
 */
public interface ProjectionHook {

    String hookId();

    void project(Map<String, Object> confirmedModel);
}

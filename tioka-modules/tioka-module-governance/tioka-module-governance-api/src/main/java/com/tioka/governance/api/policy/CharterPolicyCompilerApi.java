package com.tioka.governance.api.policy;

import java.util.List;
import java.util.Map;

/**
 * TRU-02 授权书编译端口（ADR-032「编译成 Cedar」）。Agent 版本发布时由 agent-biz 调用；
 * 实现在 governance-biz（与 PolicyStore 同模块），对每个动作类别生成一组 forbid 策略写入
 * {@code gov_policy}（source = charter:{agentVersionId}，一律 ENFORCE）并广播策略变更事件。
 */
public interface CharterPolicyCompilerApi {

    /**
     * 编译并替换该 Agent 版本的策略组（旧版本保留、新版本写入、重复调用幂等覆盖）。
     * charter 为 null / 无动作类别时清空该版本旧策略组（版本回退语义）。
     */
    void compile(CompilationCommand cmd);

    /** 版本归档时停用该版本的策略组（M3 无归档入口，预留）。 */
    void deactivate(String workspaceId, String agentVersionId);

    /**
     * 编译命令。字段与 agent-api 类型化 Charter 对齐（governance-api 不依赖 agent-api）。
     *
     * @param classes   动作类别（code / toolPatterns / limits / fourEyes / delegable）
     * @param forbiddenTools 授权书级禁区（命中即拒绝）
     */
    record CompilationCommand(
            String workspaceId,
            String agentVersionId,
            List<ActionClassSpec> classes,
            List<String> forbiddenTools) {}

    /**
     * @param scope 参数取值范围（如 {"customerLevel": ["C","D"]}）
     */
    record ActionClassSpec(
            String code,
            List<String> toolPatterns,
            Long perCallAmountMicros,
            Long dailyAmountMicros,
            Integer perRunRecords,
            Integer dailyRecords,
            Map<String, List<String>> scope,
            boolean fourEyes,
            boolean delegable) {}
}

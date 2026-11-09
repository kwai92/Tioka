package com.tioka.governance.application.policy;

import com.tioka.governance.api.policy.CharterPolicyCompilerApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TRU-02 授权书编译器（M3-05 §3.1 / ADR-032「编译成 Cedar」）。
 * Agent 版本发布时运行，对每个动作类别生成策略组（一律 ENFORCE）：
 * <ul>
 *   <li>单次金额超额 → ESCALATE（{@code escalate-amount}）</li>
 *   <li>单 Run 记录数超额 → ESCALATE（{@code escalate-per-run-records}）</li>
 *   <li>当日累计金额/记录数超额 → ESCALATE（读 context 当日累计，{@code escalate-daily-amount} /
 *       {@code escalate-daily-records}）</li>
 *   <li>参数范围外 → DENY（{@code scope}）</li>
 *   <li>禁区工具 → DENY（{@code forbidden-tool}；按委派链编写供 MAS-02：context.chain 条件）</li>
 * </ul>
 * 策略 @id = {@code charter:{agentVersionId}:{动作类别}:{规则名}}；新版本发布写新策略组、
 * 旧版本保留（钉旧版本的会话按旧授权书）、版本归档停用。
 *
 * <p>动作组写法：{@code action in Tioka::Action::"{类别}"}（Cedar 动作组只能为 Action 类型
 * 父实体层级，ADR-032 示例的 ActionClass 写法在无 schema 求值下不可解析，登记偏差）。
 */
public class DefaultCharterPolicyCompiler implements CharterPolicyCompilerApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultCharterPolicyCompiler.class);

    /** context.chain.contains("{agv}") 条件口径（M3-01 L146 / ADR-032 修订）：委派链祖先禁区生效。 */
    private static final String CHAIN_CONDITION =
            "(context.agentVersion == \"%s\" || context.chain.contains(\"%s\"))";

    private final PolicyStore store;
    private final PolicyChangeNotifier notifier;
    private final String actor;

    public DefaultCharterPolicyCompiler(PolicyStore store, PolicyChangeNotifier notifier, String actor) {
        this.store = store;
        this.notifier = notifier;
        this.actor = actor;
    }

    @Override
    public void compile(CompilationCommand cmd) {
        List<PolicyStore.CedarPolicySpec> specs = compileToSpecs(cmd);
        int version = store.replaceCharterPolicies(cmd.workspaceId(), cmd.agentVersionId(), specs, actor);
        notifier.notifyChanged(cmd.workspaceId(), version,
                PolicyStore.SOURCE_CHARTER_PREFIX + cmd.agentVersionId(), PolicyMode.ENFORCE.name());
        log.info("charter policies compiled ws={} agv={} policies={} version={}",
                cmd.workspaceId(), cmd.agentVersionId(), specs.size(), version);
    }

    /** 纯函数编译（测试断言输出策略组）。 */
    List<PolicyStore.CedarPolicySpec> compileToSpecs(CompilationCommand cmd) {
        List<PolicyStore.CedarPolicySpec> out = new ArrayList<>();
        String agv = cmd.agentVersionId();
        String agvCond = CHAIN_CONDITION.formatted(agv, agv);
        if (cmd.forbiddenTools() != null && !cmd.forbiddenTools().isEmpty()) {
            for (ActionClassSpec cls : cmd.classes() == null ? List.<ActionClassSpec>of() : cmd.classes()) {
                out.add(forbiddenToolSpec(agv, cls.code(), cmd.forbiddenTools(), agvCond));
            }
        }
        if (cmd.classes() == null) {
            return List.copyOf(out);
        }
        for (ActionClassSpec cls : cmd.classes()) {
            String group = actionGroupRef(cls.code());
            if (cls.perCallAmountMicros() != null) {
                String text = "@id(\"%s\")\n@effect(\"ESCALATE\")\nforbid(principal, action in %s, resource)\nwhen { %s && resource.amountMicros > %d };"
                        .formatted(id(agv, cls.code(), "escalate-amount"), group, agvCond, cls.perCallAmountMicros());
                out.add(new PolicyStore.CedarPolicySpec(
                        id(agv, cls.code(), "escalate-amount"), text, PolicyDecision.ESCALATE));
            }
            if (cls.perRunRecords() != null) {
                String text = "@id(\"%s\")\n@effect(\"ESCALATE\")\nforbid(principal, action in %s, resource)\nwhen { %s && resource.records > %d };"
                        .formatted(id(agv, cls.code(), "escalate-per-run-records"), group, agvCond, cls.perRunRecords());
                out.add(new PolicyStore.CedarPolicySpec(
                        id(agv, cls.code(), "escalate-per-run-records"), text, PolicyDecision.ESCALATE));
            }
            if (cls.dailyAmountMicros() != null) {
                String text = "@id(\"%s\")\n@effect(\"ESCALATE\")\nforbid(principal, action in %s, resource)\nwhen { %s && context.dailyAmountMicros > %d };"
                        .formatted(id(agv, cls.code(), "escalate-daily-amount"), group, agvCond, cls.dailyAmountMicros());
                out.add(new PolicyStore.CedarPolicySpec(
                        id(agv, cls.code(), "escalate-daily-amount"), text, PolicyDecision.ESCALATE));
            }
            if (cls.dailyRecords() != null) {
                String text = "@id(\"%s\")\n@effect(\"ESCALATE\")\nforbid(principal, action in %s, resource)\nwhen { %s && context.dailyRecords > %d };"
                        .formatted(id(agv, cls.code(), "escalate-daily-records"), group, agvCond, cls.dailyRecords());
                out.add(new PolicyStore.CedarPolicySpec(
                        id(agv, cls.code(), "escalate-daily-records"), text, PolicyDecision.ESCALATE));
            }
            if (cls.scope() != null && !cls.scope().isEmpty()) {
                String text = "@id(\"%s\")\n@effect(\"DENY\")\nforbid(principal, action in %s, resource)\nwhen { %s && !(%s) };"
                        .formatted(id(agv, cls.code(), "scope"), group, agvCond, scopeCondition(cls.scope()));
                out.add(new PolicyStore.CedarPolicySpec(
                        id(agv, cls.code(), "scope"), text, PolicyDecision.DENY));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void deactivate(String workspaceId, String agentVersionId) {
        store.deactivateCharterPolicies(workspaceId, agentVersionId);
        notifier.notifyChanged(workspaceId, store.currentSetVersion(workspaceId),
                PolicyStore.SOURCE_CHARTER_PREFIX + agentVersionId, PolicyMode.ENFORCE.name());
    }

    /** 禁区策略按委派链编写（供 MAS-02 让祖先禁区生效）：chain.contains 条件口径照 M3-01 L146。 */
    private PolicyStore.CedarPolicySpec forbiddenToolSpec(String agv, String classCode,
                                                          List<String> forbiddenTools, String agvCond) {
        String tools = forbiddenTools.stream()
                .map(t -> "Action::\"" + CedarSchemaProvider.sanitize(t) + "\"")
                .reduce((a, b) -> a + ", " + b).orElse("");
        String text = "@id(\"%s\")\n@effect(\"DENY\")\nforbid(principal, action in Tioka::Action::\"%s\", resource)\nwhen { %s && action in [%s] };"
                .formatted(id(agv, classCode, "forbidden-tool"),
                        CedarSchemaProvider.sanitize(classCode), agvCond, tools);
        return new PolicyStore.CedarPolicySpec(id(agv, classCode, "forbidden-tool"), text, PolicyDecision.DENY);
    }

    private static String id(String agv, String classCode, String rule) {
        return "charter:" + agv + ":" + CedarSchemaProvider.sanitize(classCode) + ":" + rule;
    }

    private static String actionGroupRef(String classCode) {
        return "Tioka::Action::\"" + CedarSchemaProvider.sanitize(classCode) + "\"";
    }

    /**
     * scope 条件：resource.attrs 中声明的参数取值必须在范围内。
     * Cedar 的 {@code in} 只用于实体层级成员判定，列表成员用 {@code contains}
     * （如 {@code ["C","D"].contains(resource.attrs.customerLevel)}，实测登记）。
     */
    private static String scopeCondition(Map<String, List<String>> scope) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : scope.entrySet()) {
            List<String> values = e.getValue() == null ? List.<String>of() : e.getValue();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(CedarSchemaProvider.sanitize(String.valueOf(values.get(i)))).append('"');
            }
            sb.append("].contains(resource.attrs.")
                    .append(CedarSchemaProvider.sanitize(e.getKey())).append(')');
            parts.add(sb.toString());
        }
        return String.join(" && ", parts);
    }
}

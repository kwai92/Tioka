package com.tioka.runtime.api.approval;

import com.tioka.framework.security.Principal;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工具调用审批策略 SPI（RT-07，ADR-016，施工设计 M1-02）。
 *
 * <p>Runtime 在执行每个工具调用前调用 {@link #evaluate}；按 {@code TurnOptions.approvalPolicy} 选择实现，
 * 未指定时用 {@code agent-config}。
 */
public interface ApprovalPolicy {

    /** 策略编码，如 {@code agent-config}、{@code always}、{@code never}。 */
    String code();

    Decision evaluate(ApprovalRequestContext ctx);

    /**
     * @param principal      Turn 发起人（工具实际以此身份执行）
     * @param agentVersionId Agent 版本快照
     * @param toolName       工具名
     * @param args           模型给出的参数
     * @param risk           工具声明的风险等级
     * @param options        Agent 版本中该工具的策略配置等扩展项
     */
    record ApprovalRequestContext(Principal principal, String agentVersionId, String toolName,
                                  Map<String, Object> args, ToolRisk risk, Map<String, String> options) {}

    sealed interface Decision permits Allow, Deny, RequireApproval {}

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    /**
     * @param assignee 审批人；用户与角色都为空表示 Turn 发起人
     * @param ttl      过期时间，到期置 EXPIRED
     * @param reason   展示给审批人的原因
     */
    record RequireApproval(Assignee assignee, Duration ttl, String reason) implements Decision {}

    record Assignee(List<String> userIds, List<String> roleCodes) {
        public Assignee {
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
            roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
        }

        public boolean isRequester() {
            return userIds.isEmpty() && roleCodes.isEmpty();
        }
    }
}

package com.tioka.governance.application.charter;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.governance.api.charter.AgentCharterPort;
import com.tioka.governance.api.charter.CharterEventKind;
import com.tioka.governance.api.charter.CharterEventWriter;
import com.tioka.governance.api.charter.CharterTurnInterruptPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * TRU-03 授权书生命周期状态机（M3-05 §4 / ADR-032「负责人与生命周期」）。
 *
 * <pre>
 * ACTIVE ──负责人禁用 / 移出空间──▶ SUSPENDED ──指定新负责人──▶ HANDOVER ──新负责人确认──▶ ACTIVE
 * </pre>
 *
 * <p>触发来源：M1 身份事件桥（ID-BR）的 {@code tioka.identity.user_disabled.v1} /
 * {@code tioka.identity.member_removed.v1}，经 {@code IdentityCharterConsumer}（MQ 主口径）
 * 与 {@code CharterLifecycleListener}（同实例加速）进入，两路幂等由状态转移 CAS 保证。
 * 每次状态变化写 {@code trust_charter_event}；SUSPEND 同时取消该 Agent 的运行中 Turn
 * （经 {@link CharterTurnInterruptPort}，取消信号跨实例生效）。
 * M3 不处理复核逾期（TRU-04 在 M4）；NTF-01 通知挂接登记偏离（事件落表留痕）。
 */
public class CharterLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(CharterLifecycleService.class);

    /** SUSPEND 取消运行中 Turn 的原因（写入 runtime_turn.error_code；与 runtime 侧常量同值）。 */
    public static final String REASON_CHARTER_SUSPENDED = "charter_suspended";

    private final AgentCharterPort agents;
    private final CharterTurnInterruptPort interrupts;
    private final CharterEventWriter events;
    private final PrincipalHolder principals;

    public CharterLifecycleService(AgentCharterPort agents,
                                   CharterTurnInterruptPort interrupts,
                                   CharterEventWriter events,
                                   PrincipalHolder principals) {
        this.agents = agents;
        this.interrupts = interrupts;
        this.events = events;
        this.principals = principals;
    }

    /**
     * 负责人被禁用：其名下全部 Agent（跨工作空间）ACTIVE → SUSPENDED。
     * 幂等口径（M3-05 §4「重复事件只处理一次」）＝状态转移幂等：已非 ACTIVE 的 Agent
     * CAS 影响 0 行，跳过且不重复写事件。
     */
    public void suspendByOwnerDisable(String userId) {
        Objects.requireNonNull(userId, "userId");
        // 跨空间扫描 + 行级过滤旁路：以 SYSTEM 执行（与 PrincipalTurnRevoker 同口径）
        principals.runAs(SystemPrincipals.system(), () -> {
            for (AgentCharterPort.CharterAgentView agent : agents.listOwnedBy(userId)) {
                suspendOne(agent, "owner_disabled:" + userId);
            }
            return null;
        });
    }

    /** 成员被移出空间：仅该空间内其名下 Agent ACTIVE → SUSPENDED（幂等同上）。 */
    public void suspendByMemberRemove(String workspaceId, String userId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        principals.runAs(SystemPrincipals.system(), () -> {
            for (AgentCharterPort.CharterAgentView agent : agents.listOwnedByInWorkspace(workspaceId, userId)) {
                suspendOne(agent, "member_removed:" + workspaceId + ":" + userId);
            }
            return null;
        });
    }

    /**
     * 交接：空间管理员指定新负责人（ACTIVE / SUSPENDED → HANDOVER）。
     * 权限：调用方须持 {@code workspace:manage}。
     */
    public void assignOwner(String workspaceId, String agentId, String newOwnerId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(newOwnerId, "newOwnerId");
        var principal = principals.current();
        if (principal == null || !principal.hasPermission(Permissions.WORKSPACE_MANAGE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "指定新负责人须 workspace:manage（TRU-03）");
        }
        // 转移与事件写入以 SYSTEM 执行（事件可能由跨空间管理端触发，避免行级过滤误伤）
        principals.runAs(SystemPrincipals.system(), () -> {
            AgentCharterPort.CharterAgentView agent = agents.getAgent(agentId);
            if (agent == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "agent 不存在");
            }
            requireSameWorkspace(workspaceId, agent);
            boolean changed = agents.transitionToHandover(agentId, newOwnerId);
            if (changed) {
                events.append(new CharterEventWriter.AppendCommand(
                        workspaceId, agentId, CharterEventKind.HANDOVER,
                        "owner_reassigned", agent.ownerId(), newOwnerId, principal.userId()));
                log.info("charter handover agent={} from={} to={} actor={}",
                        agentId, agent.ownerId(), newOwnerId, principal.userId());
            }
            return null;
        });
    }

    /**
     * 新负责人确认授权书（HANDOVER → ACTIVE，reviewed_at 刷新），写 ACCEPT 事件（审计）。
     * ADR-032 要求「新负责人在档案页逐条确认授权书后变为 ACTIVE」：调用者必须是交接后
     * 的当前负责人，且 workspaceId 必须与 Agent 所属空间一致，避免任意登录用户越权恢复
     * 被暂停的数字员工、或把确认事件写入别的空间。
     */
    public void acceptCharter(String workspaceId, String agentId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(agentId, "agentId");
        var principal = principals.current();
        if (principal == null) {
            throw new IllegalStateException("acceptCharter 需要登录主体");
        }
        principals.runAs(SystemPrincipals.system(), () -> {
            AgentCharterPort.CharterAgentView agent = agents.getAgent(agentId);
            if (agent == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "agent 不存在");
            }
            requireSameWorkspace(workspaceId, agent);
            if (!principal.userId().equals(agent.ownerId())) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "仅新负责人可确认授权书（TRU-03 / ADR-032）");
            }
            boolean changed = agents.transitionToActive(agentId);
            if (changed) {
                events.append(new CharterEventWriter.AppendCommand(
                        workspaceId, agentId, CharterEventKind.ACCEPT,
                        "charter_accepted", null, principal.userId(), principal.userId()));
                log.info("charter accepted agent={} by={}", agentId, principal.userId());
            }
            return null;
        });
    }

    /** 空间一致性：授权书状态变化与 trust_charter_event 必须落在 Agent 所属空间。 */
    private static void requireSameWorkspace(String workspaceId,
                                             AgentCharterPort.CharterAgentView agent) {
        if (!workspaceId.equals(agent.workspaceId())) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "授权书操作跨空间被拒（TRU-03）");
        }
    }

    private void suspendOne(AgentCharterPort.CharterAgentView agent, String reason) {
        // 状态幂等：CAS ACTIVE→SUSPENDED；false = 已 SUSPENDED/HANDOVER，重复事件只处理一次
        if (!agents.transitionToSuspended(agent.agentId())) {
            return;
        }
        events.append(new CharterEventWriter.AppendCommand(
                agent.workspaceId(), agent.agentId(), CharterEventKind.SUSPEND,
                reason, agent.ownerId(), null, "system"));
        // SUSPENDED 的效果：运行中 Turn 在下一步开始前取消（与定案 6 同一检查点）
        try {
            int n = interrupts.interruptActiveTurns(
                    agents.readableVersionIds(agent.agentId()), REASON_CHARTER_SUSPENDED);
            if (n > 0) {
                log.info("charter suspended, cancelled {} active turns agent={}", n, agent.agentId());
            }
        } catch (RuntimeException e) {
            // 取消失败不回滚状态机（SUSPENDED 已生效，新 Turn 已被 423 拦截）；留告警排查
            log.warn("charter suspend turn cancellation failed agent={}: {}", agent.agentId(), e.toString());
        }
    }
}

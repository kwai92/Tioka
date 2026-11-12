package com.tioka.governance.application.autonomy;

import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyLevel;
import com.tioka.governance.api.charter.AgentCharterPort;
import com.tioka.governance.infrastructure.autonomy.TrustAutonomyDemotionRepository;
import com.tioka.system.api.notify.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRU-10 自动降级（M3-05 §8 / ADR-034）：
 *
 * <ul>
 *   <li>{@code tioka.trust.action_undone.v1} → 该 Agent 该动作类别降一级（最低 L1）</li>
 *   <li>{@code tioka.trust.compensation_failed.v1} / {@code tioka.trust.undo_circuit_open.v1}
 *       → 全部已登记类别降到 L1</li>
 *   <li>每次变化经 {@code AutonomyApi.setLevel} 写 {@code trust_autonomy_event}（原等级、
 *       新等级、原因）与审计，并经 NTF-01 的 {@link NotificationPort} 给负责人发卡片</li>
 *   <li>幂等：一次撤销产生多条 action_undone，按（撤销范围、类别）只降一次；
 *       V226 {@code trust_autonomy_demotion} 以主键 ON CONFLICT DO NOTHING 锁定首次处理</li>
 * </ul>
 *
 * <p>负责人取 {@link AgentCharterPort#getAgent(String)}（governance-api 端口，不直连 agent 表），
 * 通知端口缺省缺席（{@code tioka.notify.bridge.enabled=false}）时静默跳过——闸门显式开启桥接。
 */
public class AutonomyDemotionService {

    private static final Logger log = LoggerFactory.getLogger(AutonomyDemotionService.class);
    private static final String TEMPLATE = "trust.autonomy.demoted";

    private final AutonomyApi autonomy;
    private final TrustAutonomyDemotionRepository ledger;
    private final ObjectProvider<NotificationPort> notifications;
    private final ObjectProvider<AgentCharterPort> agents;

    public AutonomyDemotionService(AutonomyApi autonomy,
                                   TrustAutonomyDemotionRepository ledger,
                                   ObjectProvider<NotificationPort> notifications,
                                   ObjectProvider<AgentCharterPort> agents) {
        this.autonomy = autonomy;
        this.ledger = ledger;
        this.notifications = notifications;
        this.agents = agents;
    }

    /** 单次撤销：该类别降一级（最低 L1）。重复事件只降一次。 */
    public void demoteOne(String agentId, String workspaceId, String actionClass,
                          String demotionKey, String eventId, String reason) {
        if (blank(agentId) || blank(actionClass)) {
            return;
        }
        int current;
        try {
            current = autonomy.levelOf(agentId, actionClass);
        } catch (RuntimeException e) {
            log.warn("自治等级读取失败，跳过降级 agentId={} class={}: {}", agentId, actionClass, e.toString());
            return;
        }
        int target = Math.max(1, current - 1);
        if (!ledger.tryReserve(demotionKey, workspaceId, agentId, actionClass, eventId,
                "L" + current, "L" + target)) {
            log.debug("降级事件重复，跳过 key={}", demotionKey);
            return;
        }
        if (target != current) {
            autonomy.setLevel(agentId, actionClass, AutonomyLevel.valueOf("L" + target),
                    reason == null ? "undo demotion" : reason);
        }
        notifyOwner(agentId, workspaceId, actionClass, current, target,
                reason, demotionKey);
    }

    /** 补偿失败 / 熔断：全部已登记类别降到 L1。已是 L1 的类别不重复写事件。 */
    public void demoteAll(String agentId, String workspaceId,
                          String demotionKey, String eventId, String reason) {
        if (blank(agentId)) {
            return;
        }
        if (!ledger.tryReserve(demotionKey, workspaceId, agentId, "*", eventId, null, "L1")) {
            log.debug("全类别降级事件重复，跳过 key={}", demotionKey);
            return;
        }
        int changed = 0;
        for (AutonomyApi.AutonomyView view : autonomy.list(agentId)) {
            int current = autonomy.levelOf(agentId, view.actionClass());
            if (current > 1) {
                autonomy.setLevel(agentId, view.actionClass(), AutonomyLevel.L1,
                        reason == null ? "circuit demotion" : reason);
                changed++;
            }
        }
        log.info("全类别降级完成 agentId={} changed={} key={}", agentId, changed, demotionKey);
        notifyOwner(agentId, workspaceId, "*", 0, 1, reason, demotionKey);
    }

    private void notifyOwner(String agentId, String workspaceId, String actionClass,
                             int from, int to, String reason, String demotionKey) {
        NotificationPort port = notifications.getIfAvailable();
        if (port == null) {
            // NTF-01 桥接缺省关闭（C-10 口径）：不发通知，不阻断降级写库
            log.debug("通知桥接未启用，跳过降级卡片 agentId={}", agentId);
            return;
        }
        String owner = null;
        AgentCharterPort charter = agents.getIfAvailable();
        if (charter != null) {
            try {
                AgentCharterPort.CharterAgentView view = charter.getAgent(agentId);
                owner = view == null ? null : view.ownerId();
            } catch (RuntimeException e) {
                log.debug("负责人查询失败 agentId={}: {}", agentId, e.toString());
            }
        }
        String recipient = blank(owner) ? "workspace:" + (workspaceId == null ? "" : workspaceId) : owner;
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("agentId", agentId);
        variables.put("workspaceId", workspaceId == null ? "" : workspaceId);
        variables.put("actionClass", actionClass);
        variables.put("fromLevel", "L" + from);
        variables.put("toLevel", "L" + to);
        variables.put("reason", reason == null ? "" : reason);
        variables.put("recipient", recipient);
        try {
            port.notify(new NotificationPort.NotificationCommand(
                    "im:feishu", recipient, TEMPLATE, variables,
                    "autonomy-demote:" + shortHash(demotionKey)));
        } catch (RuntimeException e) {
            // 通知失败不回滚降级（与 AutonomyChangeNotifier 同口径）
            log.warn("降级通知入队失败 agentId={}: {}", agentId, e.toString());
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

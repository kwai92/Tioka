package com.tioka.runtime.kernel.child;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.application.grant.DefaultGrantService;
import com.tioka.runtime.infrastructure.GrantStore;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAS-03 子任务回收（M3-01）：子 Turn 进入 COMPLETED / FAILED / CANCELLED 的同一事务里
 * 调用 {@link #onChildTerminal}：
 * 1. grantService.reap（WITH RECURSIVE 按 parent_id 置 REAPED）
 * 2. 父授予预算回拨：remaining += child.budget − child.used（GrantBody CAS 乐观锁）
 * 3. 未决审批：子 thread 各 Turn 的 PENDING 审批 → EXPIRED(reason=reaped)
 * afterCommit：
 * 4. sandbox 释放事件（sandboxProvider 尚不存在，发结构化事件并登记简化）
 * 5. 工作盘归档（工作盘机制尚不存在，登记简化，本批只做 1〜3 与事件）
 * 6. agent.delegate 唤醒父 Turn：子最终回答作为 tool_result（截断同 RT-10 口径）
 */
@Service
public class SubAgentReaper {
    private static final Logger log = LoggerFactory.getLogger(SubAgentReaper.class);
    /** RT-10：委派结果回填父 Turn 的截断上限。 */
    static final int ANSWER_MAX = 4000;
    public static final String REASON_REAPED = "reaped";

    private final RuntimeThreadRepository threads;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final ApprovalRepository approvals;
    private final GrantStore grantStore;
    private final DefaultGrantService grantService;
    private final ChildPendingRegistry pendingRegistry;
    private final RuntimeChildRouterConsumer childRouter;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher appEvents;
    private final com.tioka.runtime.application.label.TurnLabelTracker labelTracker;

    public SubAgentReaper(RuntimeThreadRepository threads,
                          TurnRepository turns,
                          ItemRepository items,
                          ApprovalRepository approvals,
                          GrantStore grantStore,
                          DefaultGrantService grantService,
                          ChildPendingRegistry pendingRegistry,
                          RuntimeChildRouterConsumer childRouter,
                          ObjectMapper mapper,
                          ApplicationEventPublisher appEvents,
                          com.tioka.runtime.application.label.TurnLabelTracker labelTracker) {
        this.threads = threads;
        this.turns = turns;
        this.items = items;
        this.approvals = approvals;
        this.grantStore = grantStore;
        this.grantService = grantService;
        this.pendingRegistry = pendingRegistry;
        this.childRouter = childRouter;
        this.mapper = mapper;
        this.appEvents = appEvents;
        this.labelTracker = labelTracker;
    }

    /**
     * 子 Turn 终态回收。非子 thread（无 parent_turn_id）直接返回。
     * 幂等：reap 只影响 ACTIVE 授予；审批过期只影响 PENDING。
     */
    @Transactional
    public void onChildTerminal(String childThreadId, String childTurnId, TurnStatus status) {
        if (childThreadId == null || childThreadId.isBlank()) {
            return;
        }
        RuntimeThreadDO thread = threads.find(childThreadId);
        if (thread == null || thread.getParentTurnId() == null || thread.getParentTurnId().isBlank()) {
            return;
        }
        String grantId = thread.getGrantId();
        if (grantId != null && !grantId.isBlank()) {
            // 1. 本授予与全部后代 → REAPED（缓存失效在事务提交后广播）
            grantService.reap(grantId);
            // 2. 子用量计入 + 预算回拨父授予
            long used = grantStore.sumThreadTokens(childThreadId);
            grantService.accrueAndReturnToParent(grantId, used);
        }
        // DAT-02：子 thread 终态标签回流父 thread（agent.delegate；MONOTONIC 只升不降）
        if (thread.getDataLabel() != null) {
            var parentTurn = turns.findById(thread.getParentTurnId()).orElse(null);
            if (parentTurn != null) {
                labelTracker.record(parentTurn.getThreadId(), thread.getDataLabel());
            }
        }
        // 3. 子 thread 全部 Turn 的未决审批 → EXPIRED(reason=reaped)
        for (RuntimeTurnDO t : turns.listByThread(childThreadId, 0, 500)) {
            approvals.expirePendingForSubject("TURN", t.getId(), REASON_REAPED);
        }
        final String childTurn = childTurnId;
        final TurnStatus st = status == null ? TurnStatus.FAILED : status;
        final String threadId = childThreadId;
        afterCommit(() -> {
            // 4. sandbox 释放事件（简化：结构化事件，sandboxProvider 于后续批次接入）
            appEvents.publishEvent(new SubAgentSandboxReleased(childThreadId, st.name()));
            // 6. 唤醒父 Turn（agent.delegate 挂起登记）
            boolean ok = st == TurnStatus.COMPLETED;
            ChildPendingRegistry.Entry pending = pendingRegistry.take(childTurn).orElse(null);
            if (pending != null) {
                childRouter.routeLocally(pending.turnId(), pending.callId(),
                        ok, finalAnswer(threadId), ok ? null : st.name());
            } else {
                // C-17 修复：子 Turn 可能由另一实例执行（双实例部署），而挂起登记
                // pendingRegistry 是执行 delegate 的实例的进程内存 —— 本进程 take 不中
                // 不代表「非 delegate 路径」。经 DB 反查恢复：子 thread.parent_turn_id
                // → 父 Turn → 带 pending.jobId=子 Turn 的 tool_result 占位（callId 在内），
                // 再走 routeLocally（ChildResumeService 本身 DB/Redis 层跨实例安全）。
                resumeFromDb(thread, childTurn, ok, st);
            }
        });
        log.info("subagent reaped thread={} turn={} status={} grant={}",
                childThreadId, childTurnId, st, grantId);
    }

    /** 跨实例唤醒：按 pending.jobId=子 Turn 反查父 Turn 的挂起占位并续跑（C-17）。 */
    private void resumeFromDb(RuntimeThreadDO childThread, String childTurnId,
                              boolean ok, TurnStatus st) {
        String parentTurnId = childThread.getParentTurnId();
        if (parentTurnId == null || parentTurnId.isBlank()) {
            return;
        }
        if (turns.findById(parentTurnId).orElse(null) == null) {
            return;
        }
        for (RuntimeItemDO i : items.listByTurn(parentTurnId)) {
            if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                continue;
            }
            JsonNode root = readNode(i.getContentJson());
            if (root == null || !childTurnId.equals(root.path("pending").path("jobId").asText(null))) {
                continue;
            }
            String callId = root.path("callId").asText(null);
            if (callId != null && !callId.isBlank()) {
                childRouter.routeLocally(parentTurnId, callId,
                        ok, finalAnswer(childThread.getId()), ok ? null : st.name());
            }
            return;
        }
    }

    private JsonNode readNode(String contentJson) {
        try {
            return mapper.readTree(contentJson == null ? "{}" : contentJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** 子 thread 最终回答：最后一条 assistant message 的 text（截断同 RT-10）。 */
    private String finalAnswer(String childThreadId) {
        String best = null;
        for (RuntimeItemDO i : items.listAfterSeq(childThreadId, 0, 500)) {
            if (!"assistant".equals(i.getRole()) || !ItemType.MESSAGE.wire().equals(i.getType())) {
                continue;
            }
            String text = readText(i.getContentJson());
            if (text != null && !text.isBlank()) {
                best = text;
            }
        }
        if (best == null) {
            return "";
        }
        return best.length() <= ANSWER_MAX ? best : best.substring(0, ANSWER_MAX);
    }

    private String readText(String contentJson) {
        try {
            JsonNode n = mapper.readTree(contentJson == null ? "{}" : contentJson);
            return n.path("text").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** afterCommit 结构化事件：sandbox 释放（简化口径，登记于批次文档）。 */
    public record SubAgentSandboxReleased(String childThreadId, String status) {
        public String eventId() {
            return Ids.newId("sbxr");
        }

        public Map<String, Object> payload() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("childThreadId", childThreadId);
            m.put("status", status);
            return m;
        }
    }
}

package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.grant.TaskNarrow;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.grant.GrantAgentViews;
import com.tioka.runtime.infrastructure.GrantStore;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RT-12：{@code agent.delegate} 内置工具（M3-01 L164-171 六步）。
 *
 * <p>注册挂 {@code tioka.runtime.delegate.enabled}（默认 <b>false</b>，MAS-01 合并后才开）；
 * 关闭时 listTools 不含它。委派走新路径（fork() NotImplemented 不变）：
 * 同一事务 narrow + 建 child thread(parent_turn_id=父Turn, depth+1, grant_id) + QUEUED turn
 * （origin=delegate，run_as 不变）；afterCommit XADD rt:turns；父 Turn 经 PendingJob 挂起
 * WAITING_CHILD，子终态由 {@code SubAgentReaper} 唤醒（最终回答作 tool_result，截断同 RT-10）。
 */
@Component
public class DelegateToolProvider implements ToolProvider {
    public static final String TOOL_NAME = "agent.delegate";

    private static final Logger log = LoggerFactory.getLogger(DelegateToolProvider.class);

    private final KernelProperties props;
    private final AgentSnapshotApi agents;
    private final GrantService grants;
    private final GrantStore grantStore;
    private final RuntimeThreadRepository threads;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final TurnDispatchQueue dispatchQueue;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    /** 延迟解析（ToolRegistry ← Provider 列表 ← 本类，构造期循环依赖）。 */
    private final org.springframework.beans.factory.ObjectProvider<ToolRegistry> toolRegistry;

    public DelegateToolProvider(KernelProperties props,
                                AgentSnapshotApi agents,
                                GrantService grants,
                                GrantStore grantStore,
                                RuntimeThreadRepository threads,
                                TurnRepository turns,
                                ItemRepository items,
                                TurnDispatchQueue dispatchQueue,
                                TransactionTemplate tx,
                                ObjectMapper mapper,
                                org.springframework.beans.factory.ObjectProvider<ToolRegistry> toolRegistry) {
        this.props = props;
        this.agents = agents;
        this.grants = grants;
        this.grantStore = grantStore;
        this.threads = threads;
        this.turns = turns;
        this.items = items;
        this.dispatchQueue = dispatchQueue;
        this.tx = tx;
        this.mapper = mapper;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        // RT-12 特性开关：默认 false 不注册（listTools 不含 agent.delegate）
        if (!props.getDelegate().isEnabled()) {
            return List.of();
        }
        return List.of(ToolDescriptor.builder()
                .name(TOOL_NAME)
                .description("委派子 Agent 执行任务；权限只随委派收窄（narrow），父 Turn 等待子结果")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "agentId", Map.of("type", "string"),
                                "task", Map.of("type", "string"),
                                "context", Map.of("type", "string"),
                                "narrow", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "tools", Map.of("type", "array", "items", Map.of("type", "string")),
                                                "resources", Map.of("type", "object"),
                                                "scope", Map.of("type", "object"),
                                                "budgetTokens", Map.of("type", "integer")))),
                        "required", List.of("agentId", "task")))
                .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        return new ToolResult(false, "agent.delegate 须带 ToolCallContext 调用");
    }

    @Override
    public ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments,
                             Map<String, Object> agentToolConfig) {
        if (!TOOL_NAME.equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        // ctx 缺失（直接 SPI 调用）→ 无父 Turn 可挂，拒绝
        return invoke(null, agentVersionId, toolName, arguments, agentToolConfig);
    }

    @Override
    public ToolResult invoke(ToolGateway.ToolCallContext ctx, String agentVersionId, String toolName,
                             Map<String, Object> arguments, Map<String, Object> agentToolConfig) {
        if (!TOOL_NAME.equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String agentId = text(args.get("agentId"));
        String task = text(args.get("task"));
        if (agentId == null || task == null) {
            return new ToolResult(false, "agentId 与 task 必填");
        }
        String parentVersionId = agentVersionId;
        if (parentVersionId == null || parentVersionId.isBlank()) {
            return new ToolResult(false, "缺少父 agentVersionId");
        }
        if (ctx == null || ctx.turnId() == null || ctx.threadId() == null) {
            return new ToolResult(false, "agent.delegate 缺少 ToolCallContext");
        }

        // 1. agentId ∈ 父快照.subAgents.allowed（快照 subAgents 预留 null → 不允许委派）
        AgentSnapshotApi.AgentSnapshot parentSnap = agents.get(parentVersionId);
        if (!allowedByParent(parentSnap, agentId)) {
            return new ToolResult(false, "agent 不在父快照 subAgents.allowed 内: " + agentId);
        }

        // 2. 并行数：本父 Turn 已在跑的子 Turn 数 < maxParallel
        int running = grantStore.countActiveChildTurns(ctx.turnId());
        if (running >= props.getDelegate().getMaxParallel()) {
            return new ToolResult(false, "并行数已达上限 " + props.getDelegate().getMaxParallel());
        }

        // 3. child = publishedSnapshot；charter 状态检查（TRU-02 阻塞，stub 放行，登记偏差）
        AgentSnapshotApi.AgentSnapshot childSnap;
        try {
            childSnap = agents.currentPublished(agentId);
        } catch (RuntimeException e) {
            return new ToolResult(false, "子 agent 未发布: " + agentId);
        }
        if (childSnap == null) {
            return new ToolResult(false, "子 agent 未发布: " + agentId);
        }

        // 父授予（startTurn 根授予 / 上级 narrow 已写 thread.grant_id → ToolCallContext.grantId）
        DelegationGrant parentGrant = ctx.grantId() == null ? null : grants.get(ctx.grantId());
        if (parentGrant == null || parentGrant.status() != GrantStatus.ACTIVE) {
            return new ToolResult(false, "父授予不存在或已失效，不能委派");
        }

        // 4. 同一事务：narrow + 建 thread + QUEUED turn（run_as 不变，origin=delegate）
        TaskNarrow narrow = parseNarrow(args.get("narrow"));
        GrantAgentView childView = GrantAgentViews.fromSnapshot(childSnap, toolRegistry.getObject());
        String parentTurnId = ctx.turnId();
        String parentThreadId = ctx.threadId();
        String[] plan = tx.execute(status -> {
            DelegationGrant childGrant = grants.narrow(parentGrant, childView, narrow);
            RuntimeThreadDO parentThread = threads.find(parentThreadId);

            RuntimeThreadDO child = new RuntimeThreadDO();
            child.setId(Ids.newId("thr"));
            child.setStatus("OPEN");
            child.setAgentVersionId(childSnap.versionId());
            child.setRoutingPlanId(childSnap.spec().model().routingPlanId());
            child.setWorkspaceId(parentThread == null ? null : parentThread.getWorkspaceId());
            child.setOwnerId(parentThread == null ? null : parentThread.getOwnerId());
            child.setLastItemSeq(0L);
            child.setOrigin("delegate");
            child.setOriginRef(parentTurnId);
            child.setParentTurnId(parentTurnId);
            child.setDepth((parentThread == null || parentThread.getDepth() == null ? 0 : parentThread.getDepth()) + 1);
            child.setGrantId(childGrant.grantId());
            threads.upsert(child);

            RuntimeTurnDO parentTurn = turns.findById(parentTurnId).orElse(null);
            RuntimeTurnDO t = new RuntimeTurnDO();
            String childTurnId = Ids.newId("turn");
            t.setId(childTurnId);
            t.setThreadId(child.getId());
            t.setSeq(turns.nextSeq(child.getId()));
            t.setStatus(TurnStatus.QUEUED.name());
            t.setOrigin("delegate");
            t.setOriginRef(parentTurnId);
            t.setClientMsgId("dlg:" + parentTurnId + ":" + childTurnId);
            // run_as = 父 run_as（不变，ADR-039：名义全链不变）
            t.setRunAsJson(parentTurn == null ? null : parentTurn.getRunAsJson());
            t.setOptionsJson(parentTurn == null ? null : parentTurn.getOptionsJson());
            t.setStepCount(0);
            t.setLeaseVersion(0L);
            turns.insert(t);

            items.appendWithoutLease(ItemWrite.withoutLease(child.getId(), childTurnId, 0,
                    "message", "user", json(Map.of("text", task))));
            return new String[]{child.getId(), childTurnId};
        });
        if (plan == null) {
            return new ToolResult(false, "委派事务失败");
        }

        // 5. afterCommit：XADD rt:turns；父 Turn 经 PendingJob 挂起 WAITING_CHILD（ChildWaitService 登记）
        String childThreadId = plan[0];
        String childTurnId = plan[1];
        Runnable offer = () -> dispatchQueue.offer(childTurnId, childThreadId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    offer.run();
                }
            });
        } else {
            offer.run();
        }
        log.info("delegate: parentTurn={} childThread={} childTurn={} agent={}",
                parentTurnId, childThreadId, childTurnId, agentId);
        // jobId = childTurnId：子终态 Reaper 据此取 (parentTurn, callId) 唤醒父
        return new ToolResult(true, "accepted",
                new PendingJob("agent.delegate", childTurnId, Duration.ofMinutes(30)));
    }

    private boolean allowedByParent(AgentSnapshotApi.AgentSnapshot parentSnap, String agentId) {
        JsonNode sub = parentSnap.spec().subAgents();
        if (sub == null) {
            return false;
        }
        JsonNode allowed = sub.isObject() ? sub.path("allowed") : sub;
        if (!allowed.isArray()) {
            return false;
        }
        for (JsonNode n : allowed) {
            if (agentId.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private TaskNarrow parseNarrow(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new TaskNarrow(null, null, null, null);
        }
        Set<String> tools = readStrings(m.get("tools"));
        Map<String, Object> resourcesRaw = m.get("resources") instanceof Map<?, ?> r
                ? (Map<String, Object>) r : Map.of();
        Map<String, Set<String>> resources = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : resourcesRaw.entrySet()) {
            Set<String> ids = readStrings(e.getValue());
            if (!ids.isEmpty()) {
                resources.put(e.getKey(), ids);
            }
        }
        Map<String, Object> scopeRaw = m.get("scope") instanceof Map<?, ?> sc
                ? (Map<String, Object>) sc : Map.of();
        Map<String, List<String>> scope = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : scopeRaw.entrySet()) {
            Set<String> vals = readStrings(e.getValue());
            if (!vals.isEmpty()) {
                scope.put(e.getKey(), new ArrayList<>(vals));
            }
        }
        Budget budget = null;
        Object budgetTokens = m.get("budgetTokens");
        if (budgetTokens instanceof Number n && n.longValue() > 0) {
            budget = new Budget(BigDecimal.valueOf(n.longValue()), null, null);
        }
        return new TaskNarrow(tools, resources, scope, budget);
    }

    @SuppressWarnings("unchecked")
    private Set<String> readStrings(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static String text(Object o) {
        String v = o == null ? null : String.valueOf(o);
        return v == null || v.isBlank() ? null : v;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}

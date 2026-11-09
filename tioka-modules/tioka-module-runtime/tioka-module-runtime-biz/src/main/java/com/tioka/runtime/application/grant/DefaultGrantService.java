package com.tioka.runtime.application.grant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.ChainHop;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.grant.MandateScope;
import com.tioka.runtime.api.grant.TaskNarrow;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.GrantBody;
import com.tioka.runtime.infrastructure.GrantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MAS-01 委派授予实现（ADR-039 / M3-01）。
 * 签发、收窄、回收、Redis 缓存经 {@link GrantCacheStore} 抽象。
 */
@Service
public class DefaultGrantService implements GrantService {
    private static final Logger log = LoggerFactory.getLogger(DefaultGrantService.class);
    private static final int CAS_RETRY = 3;

    private final GrantStore store;
    private final GrantCacheStore cache;
    private final KernelProperties props;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> agentSnapshots;

    public DefaultGrantService(GrantStore store, GrantCacheStore cache, KernelProperties props,
                               ObjectMapper mapper,
                               org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi> agentSnapshots) {
        this.store = store;
        this.cache = cache;
        this.props = props;
        this.mapper = mapper;
        this.agentSnapshots = agentSnapshots;
    }

    @Override
    @Transactional
    public DelegationGrant root(Principal runAs, String onBehalfOf, GrantAgentView agent,
                                MandateScope mandate) {
        if (agent == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "GrantAgentView 必填");
        }
        Instant now = Instant.now();
        // charter 为 null（TRU-02 未落地）→ classes 空集（M3-01 L37-38 语义）
        Set<String> classes = agent.actionClasses() == null ? Set.of() : agent.actionClasses();
        Map<String, Set<String>> resources = agent.resources() == null ? Map.of() : agent.resources();
        DelegationGrant grant = new DelegationGrant(
                Ids.newId("grt"), null,
                refOf(runAs),
                onBehalfOf,
                List.of(new ChainHop(agent.versionId(), null)),
                agent.tools() == null ? Set.of() : Set.copyOf(agent.tools()),
                classes,
                agent.skillVersionIds() == null ? Set.of() : Set.copyOf(agent.skillVersionIds()),
                resources,
                mandate == null ? Map.of() : mandate.scope(),
                agent.dataClearance(),
                agent.egressHosts() == null ? Set.of() : Set.copyOf(agent.egressHosts()),
                agent.limitsBudget() == null ? Budget.empty() : agent.limitsBudget(),
                now.plus(props.getRootGrantTtl()),
                GrantStatus.ACTIVE,
                null);
        GrantBody body = new GrantBody(grant, 0L, 1L);
        store.insert(body, null, grant.rootPrincipalRef(), grant.onBehalfOf(),
                writeJson(grant.chain()), 1, grant.expiresAt());
        return grant;
    }

    @Override
    @Transactional
    public DelegationGrant narrow(DelegationGrant parent, GrantAgentView child, TaskNarrow narrow) {
        if (parent == null || parent.grantId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父授予必填");
        }
        GrantBody parentBody = store.findBody(parent.grantId());
        if (parentBody == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "父授予不存在: " + parent.grantId());
        }
        // TRU-02 回接：parentCharterClasses = 父授权书 delegable 类别（MAS-02 语义）；
        // 父快照/授权书不可得时回退父授予 actionClasses（保持行为兼容，登记偏差）
        DelegationGrant childGrant = NarrowingVerifier.narrow(parentBody.grant(), child, narrow,
                parentCharterClasses(parentBody.grant()), parentBody.usedTokens(),
                props.getChildGrantTtl(), props.getDelegate().getMaxDepth(), Instant.now());
        GrantBody body = new GrantBody(childGrant, 0L, 1L);
        store.insert(body, parent.grantId(), childGrant.rootPrincipalRef(), childGrant.onBehalfOf(),
                writeJson(childGrant.chain()), childGrant.chain().size(), childGrant.expiresAt());
        return childGrant;
    }

    @Override
    @Transactional
    public void reap(String grantId) {
        if (grantId == null || grantId.isBlank()) {
            return;
        }
        List<String> affectedRaw = store.reap(grantId, GrantStatus.REAPED.name());
        final List<String> affected = affectedRaw == null ? List.of() : affectedRaw;
        afterCommit(() -> {
            cache.evict(affected);
            cache.publishRevoked(affected);
        });
        if (!affected.isEmpty()) {
            log.info("grant reaped: {} (cascade {} grants)", grantId, affected.size());
        }
    }

    /** MAS-03：子 thread 用量计入授予、预算回拨到父授予（body CAS 乐观锁）。 */
    @Transactional
    public void accrueAndReturnToParent(String childGrantId, long childUsedTokens) {
        GrantBody child = store.findBody(childGrantId);
        if (child == null) {
            return;
        }
        // 子授予自身用量累计
        GrantBody childNew = new GrantBody(child.grant(), child.usedTokens() + childUsedTokens,
                child.version() + 1);
        casLoop(childGrantId, child, childNew);
        cache.evict(List.of(childGrantId));

        String parentId = child.grant().parentGrantId();
        if (parentId == null) {
            return;
        }
        // 父剩余 += 子预算 − 子已用（ADR-039：划拨即冻结，回收返还）
        for (int i = 0; i < CAS_RETRY; i++) {
            GrantBody parent = store.findBody(parentId);
            if (parent == null) {
                return;
            }
            long childBudget = child.grant().budget() == null || child.grant().budget().amountLimit() == null
                    ? 0 : child.grant().budget().amountLimit().longValue();
            long returned = Math.max(0, childBudget - childUsedTokens);
            GrantBody parentNew = new GrantBody(parent.grant(),
                    Math.max(0, parent.usedTokens() - returned), parent.version() + 1);
            if (casLoop(parentId, parent, parentNew)) {
                cache.evict(List.of(parentId));
                return;
            }
        }
    }

    /**
     * 父授权书 delegable 类别（MAS-02）：取父授予链当前 hop 版本的类型化 Charter。
     * 快照缺失 / 无授权书 / 解析失败 → 父授予 actionClasses（兼容回退）。
     */
    private Set<String> parentCharterClasses(DelegationGrant parent) {
        Set<String> fallback = parent.actionClasses() == null ? Set.of() : parent.actionClasses();
        if (agentSnapshots.getIfAvailable() == null || parent.chain() == null
                || parent.chain().isEmpty()) {
            return fallback;
        }
        String parentVersionId = parent.chain().get(parent.chain().size() - 1).agentVersionId();
        try {
            com.tioka.agent.api.spec.AgentSpec spec =
                    agentSnapshots.getIfAvailable().get(parentVersionId).spec();
            com.tioka.agent.api.spec.Charter charter =
                    spec == null ? null : com.tioka.agent.api.spec.Charter.parse(spec.charter());
            if (charter == null) {
                return fallback;
            }
            Set<String> delegable = Set.copyOf(charter.delegableClasses());
            return delegable.isEmpty() ? fallback : delegable;
        } catch (RuntimeException e) {
            log.debug("父授权书 delegable 解析失败 version={}: {}", parentVersionId, e.toString());
            return fallback;
        }
    }

    @Override
    public DelegationGrant get(String grantId) {
        if (grantId == null || grantId.isBlank()) {
            return null;
        }
        String cached = cache.get(grantId);
        if (cached != null && !cached.isBlank()) {
            try {
                return mapper.readValue(cached, GrantBody.class).grant();
            } catch (Exception ignored) {
                // 缓存损坏 → 回源
            }
        }
        GrantBody body = store.findBody(grantId);
        if (body == null) {
            return null;
        }
        try {
            cache.put(grantId, mapper.writeValueAsString(body));
        } catch (Exception ignored) {
            // 缓存写失败不阻断
        }
        return body.grant();
    }

    private static String refOf(Principal p) {
        return p == null ? "system" : p.type().name().toLowerCase(java.util.Locale.ROOT) + ":" + p.userId();
    }

    private boolean casLoop(String grantId, GrantBody expected, GrantBody updated) {
        for (int i = 0; i < CAS_RETRY; i++) {
            if (store.casBody(grantId, expected, updated)) {
                return true;
            }
            GrantBody latest = store.findBody(grantId);
            if (latest == null) {
                return false;
            }
            expected = latest;
            updated = new GrantBody(updated.grant(), updated.usedTokens(), latest.version() + 1);
        }
        throw new BizException(ErrorCode.CONFLICT, "授予预算并发冲突: " + grantId);
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

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "授予 JSON 序列化失败");
        }
    }
}

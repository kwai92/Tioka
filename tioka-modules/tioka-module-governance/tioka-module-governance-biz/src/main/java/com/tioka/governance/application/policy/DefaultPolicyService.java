package com.tioka.governance.application.policy;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyHit;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.PolicyEvalException;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GOV-07 求值与管理实现（M3-05 §1.1/§1.3/§1.4）。
 * 超时/出错：ENFORCE→DENY+审计（policy_error）；DRYRUN→ALLOW+告警。
 * DRYRUN 策略命中：结果仍按 ALLOW/ESCALATE 语义返回由调用方处理，审计记「本应拒绝」与策略 @id。
 */
public class DefaultPolicyService implements PolicyApi, PolicyAdminApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultPolicyService.class);

    private final PolicyStore store;
    private final PolicyCache cache;
    private final PolicyEvaluator evaluator;
    private final PolicyChangeNotifier notifier;
    private final PolicyProperties props;
    private final ObjectProvider<AuditApi> auditApi;
    private final PrincipalHolder principals;

    public DefaultPolicyService(PolicyStore store,
                                PolicyCache cache,
                                PolicyEvaluator evaluator,
                                PolicyChangeNotifier notifier,
                                PolicyProperties props,
                                ObjectProvider<AuditApi> auditApi,
                                PrincipalHolder principals) {
        this.store = store;
        this.cache = cache;
        this.evaluator = evaluator;
        this.notifier = notifier;
        this.props = props;
        this.auditApi = auditApi;
        this.principals = principals;
    }

    // ---------- PolicyApi ----------

    @Override
    public PolicyEvalResult evaluate(PolicyEvalRequest request) {
        String workspaceId = workspaceIdOf(request);
        PolicyCache.Entry entry = cache.get(workspaceId);
        try {
            return evaluator.evaluate(request, entry.policies(), entry.version(),
                    props.getEvalTimeoutMs());
        } catch (PolicyEvalException e) {
            // 求值超时/出错：按各策略模式处理不了——按集合内是否存在 ENFORCE 决定
            boolean anyEnforce = entry.policies().stream().anyMatch(p -> p.mode() == PolicyMode.ENFORCE);
            auditPolicyError(workspaceId, request, e.getMessage(), anyEnforce);
            if (anyEnforce) {
                log.warn("policy eval failed (ENFORCE→DENY) ws={} action={}: {}",
                        workspaceId, request.action(), e.getMessage());
                return new PolicyEvalResult(PolicyDecision.DENY, List.of(),
                        entry.version() + "", true);
            }
            log.warn("policy eval failed (DRYRUN→ALLOW) ws={} action={}: {}",
                    workspaceId, request.action(), e.getMessage());
            return new PolicyEvalResult(PolicyDecision.ALLOW, List.of(),
                    entry.version() + "", true);
        }
    }

    private static String workspaceIdOf(PolicyEvalRequest request) {
        Object ws = request.context() == null ? null : request.context().get("workspaceId");
        return ws == null ? "" : String.valueOf(ws);
    }

    // ---------- PolicyAdminApi ----------

    @Override
    public List<PolicyView> list(String workspaceId, String source) {
        return store.list(workspaceId, source);
    }

    @Override
    public PolicyView detail(String policyRowId) {
        return store.detail(policyRowId);
    }

    @Override
    public PolicyView importPolicy(ImportCommand cmd) {
        GovPolicyDO row = store.importPolicy(cmd);
        PolicyView view = store.detail(row.getId());
        notifier.notifyChanged(row.getWorkspaceId(), store.currentSetVersion(row.getWorkspaceId()),
                row.getSource(), row.getMode());
        audit("gov.policy.import", row.getWorkspaceId(), row.getPolicyId(),
                Map.of("mode", row.getMode(), "source", row.getSource()), AuditOutcome.SUCCESS);
        return view;
    }

    @Override
    public PolicyView setMode(String policyRowId, PolicyMode mode) {
        requireGovPolicyAdmin();
        GovPolicyDO row = store.setMode(policyRowId, mode);
        PolicyView view = store.detail(policyRowId);
        notifier.notifyChanged(row.getWorkspaceId(), store.currentSetVersion(row.getWorkspaceId()),
                row.getSource(), row.getMode());
        audit("gov.policy.mode_changed", row.getWorkspaceId(), row.getPolicyId(),
                Map.of("mode", row.getMode()), AuditOutcome.SUCCESS);
        return view;
    }

    @Override
    public PolicyView disable(String policyRowId) {
        requireGovPolicyAdmin();
        GovPolicyDO row = store.disable(policyRowId);
        PolicyView view = store.detail(policyRowId);
        notifier.notifyChanged(row.getWorkspaceId(), store.currentSetVersion(row.getWorkspaceId()),
                row.getSource(), row.getMode());
        audit("gov.policy.disable", row.getWorkspaceId(), row.getPolicyId(),
                Map.of("source", row.getSource()), AuditOutcome.SUCCESS);
        return view;
    }

    private void requireGovPolicyAdmin() {
        Principal p = principals == null ? null : principals.current();
        if (p == null || !p.hasPermission(Permissions.GOV_POLICY_ADMIN)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少 gov:policy:admin");
        }
    }

    /** DRYRUN 命中的「本应拒绝/本应转审批」审计由调用方（管线第 9 步）按结果写；此处仅记错误。 */
    private void auditPolicyError(String workspaceId, PolicyEvalRequest request,
                                  String error, boolean enforceDeny) {
        audit("tool.policy_eval_failed", workspaceId, request.action(),
                Map.of("error", error == null ? "" : error,
                        "policyError", true,
                        "enforceDeny", enforceDeny,
                        "reason", "policy_error"),
                enforceDeny ? AuditOutcome.DENIED : AuditOutcome.SUCCESS);
    }

    private void audit(String action, String workspaceId, String resourceId,
                       Map<String, Object> details, AuditOutcome outcome) {
        AuditApi api = auditApi == null ? null : auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Principal p = principals == null ? null : principals.current();
        AuditEvent.Builder b = AuditEvent.builder()
                .ts(Instant.now())
                .action(action)
                .resourceType("gov_policy")
                .resourceId(resourceId)
                .outcome(outcome)
                .details(details);
        if (workspaceId != null) {
            b.workspaceId(workspaceId);
        }
        if (p != null) {
            b.actorType(p.type().name().toLowerCase(Locale.ROOT)).actorId(p.userId());
        }
        try {
            if (outcome == AuditOutcome.SUCCESS) {
                api.record(b.build());
            } else {
                api.recordIndependent(b.build());
            }
        } catch (RuntimeException e) {
            log.warn("policy 审计写入失败: {}", e.toString());
        }
    }
}

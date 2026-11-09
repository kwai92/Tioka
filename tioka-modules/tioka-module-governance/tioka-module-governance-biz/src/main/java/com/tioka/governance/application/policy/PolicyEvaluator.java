package com.tioka.governance.application.policy;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.value.Value;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyHit;
import com.tioka.governance.api.policy.PolicyMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GOV-07 两遍求值器（M3-05 §1.3）。只有本类依赖 cedar-java（ArchUnit cedarOnlyInGovernance）。
 *
 * <p>两遍求值：第一遍「基线 permit + 全部 DENY 组 forbid」命中即 DENY；
 * 否则第二遍「基线 + 全部 ESCALATE 组 forbid」命中即 ESCALATE；否则 ALLOW。
 * forbid 的 @effect 注解取 DENY 或 ESCALATE，无注解按 DENY（注解在写入端落库 effect 列）。
 * RBAC 已在第 3 步执行，策略集只含一条系统内置基线 permit 与 forbid 组（导入/编译 permit 写入端拒绝）。
 */
public final class PolicyEvaluator {

    public static final String BASELINE_PERMIT = "permit(principal, action, resource);";
    private static final String BASELINE_ID = "baseline";

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final CedarSchemaProvider schema;

    public PolicyEvaluator(CedarSchemaProvider schema) {
        this.schema = schema;
    }

    public PolicyEvaluator() {
        this(new CedarSchemaProvider(List.of()));
    }

    /**
     * 两遍求值。求值失败/超时抛 {@link PolicyEvalException}，由调用方按模式处理
     * （ENFORCE→DENY+审计 policy_error，DRYRUN→ALLOW+告警）。
     *
     * @param timeoutMs 单次求值超时（tioka.policy.eval-timeout-ms，默认 20）
     */
    public PolicyEvalResult evaluate(PolicyEvalRequest request,
                                     List<ResolvedPolicy> policies,
                                     int policySetVersion,
                                     long timeoutMs) throws PolicyEvalException {
        List<ResolvedPolicy> denyGroup = new ArrayList<>();
        List<ResolvedPolicy> escalateGroup = new ArrayList<>();
        for (ResolvedPolicy p : policies) {
            if (!p.forbid()) {
                continue; // 非 forbid（异常 permit）不参与收紧，写入端已拒绝、此处兜底忽略
            }
            if (p.effect() == PolicyDecision.ESCALATE) {
                escalateGroup.add(p);
            } else {
                denyGroup.add(p);
            }
        }

        List<PolicyHit> hits = new ArrayList<>();
        try {
            Set<Policy> matchedDeny = evalOnce(request, policySet(denyGroup), timeoutMs);
            if (!matchedDeny.isEmpty()) {
                collectHits(matchedDeny, denyGroup, hits);
                return new PolicyEvalResult(PolicyDecision.DENY, List.copyOf(hits),
                        String.valueOf(policySetVersion), false);
            }
            Set<Policy> matchedEscalate = evalOnce(request, policySet(escalateGroup), timeoutMs);
            if (!matchedEscalate.isEmpty()) {
                collectHits(matchedEscalate, escalateGroup, hits);
                return new PolicyEvalResult(PolicyDecision.ESCALATE, List.copyOf(hits),
                        String.valueOf(policySetVersion), false);
            }
            return new PolicyEvalResult(PolicyDecision.ALLOW, List.of(),
                    String.valueOf(policySetVersion), false);
        } catch (PolicyEvalException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PolicyEvalException(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    /** 单遍求值，返回命中的 forbid 策略（去掉基线 permit）。 */
    private Set<Policy> evalOnce(PolicyEvalRequest request, PolicySet set, long timeoutMs)
            throws PolicyEvalException {
        AuthorizationRequest req = buildRequest(request);
        AuthorizationResponse resp;
        try {
            resp = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> uncheckedEval(req, set, request)).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new PolicyEvalException("policy_eval_timeout", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new PolicyEvalException(c.getMessage() == null
                    ? c.getClass().getSimpleName() : c.getMessage(), c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolicyEvalException("policy_eval_interrupted", e);
        }
        if (resp == null || resp.type == AuthorizationResponse.SuccessOrFailure.Failure) {
            String detail = resp != null && resp.errors.isPresent() && !resp.errors.get().isEmpty()
                    ? resp.errors.get().get(0).message : "unknown";
            throw new PolicyEvalException("policy_eval_error: " + detail, null);
        }
        AuthorizationSuccessResponse success = resp.success.orElseThrow();
        Set<Policy> matched = new HashSet<>();
        if (success.getDecision() == AuthorizationSuccessResponse.Decision.Deny) {
            Map<String, Policy> byId = new LinkedHashMap<>();
            for (Policy p : set.policies) {
                byId.put(p.getID(), p);
            }
            for (String id : success.getReason()) {
                Policy p = byId.get(id);
                if (p != null && !BASELINE_ID.equals(p.getID())) {
                    matched.add(p);
                }
            }
        }
        return matched;
    }

    private AuthorizationResponse uncheckedEval(AuthorizationRequest req, PolicySet set,
                                                PolicyEvalRequest original) {
        try {
            return engine.isAuthorized(req, set, schema.entitiesOf(original));
        } catch (com.cedarpolicy.model.exception.AuthException e) {
            throw new IllegalStateException(e.getMessage() == null
                    ? "auth_error" : e.getMessage(), e);
        }
    }

    private AuthorizationRequest buildRequest(PolicyEvalRequest request) {
        Map<String, Value> context = schema.contextValues(request.context());
        return new AuthorizationRequest(
                schema.userEuid(request.principalRef()),
                schema.actionEuid(request.action()),
                schema.toolEuid(request.resourceId(), request.action()),
                Optional.of(context), Optional.empty(), false);
    }

    private void collectHits(Set<Policy> matched, List<ResolvedPolicy> group, List<PolicyHit> hits) {
        Map<String, ResolvedPolicy> byId = new HashMap<>();
        for (ResolvedPolicy p : group) {
            byId.put(p.policyId(), p);
        }
        for (Policy p : matched) {
            ResolvedPolicy rp = byId.get(p.getID());
            if (rp != null) {
                hits.add(new PolicyHit(rp.policyId(), rp.mode(), rp.effect()));
            }
        }
    }

    private static PolicySet policySet(List<ResolvedPolicy> group) throws PolicyEvalException {
        Set<Policy> policies = new HashSet<>();
        try {
            policies.add(new Policy(BASELINE_PERMIT, BASELINE_ID));
            for (ResolvedPolicy p : group) {
                policies.add(new Policy(p.cedarText(), p.policyId()));
            }
        } catch (Exception e) {
            throw new PolicyEvalException("policy_parse_error: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        return new PolicySet(policies);
    }

    /** 已解析策略（DB 行投影），便于离线测试与缓存。 */
    public record ResolvedPolicy(
            String policyId,
            String cedarText,
            boolean forbid,
            PolicyDecision effect,
            PolicyMode mode) {

        public static ResolvedPolicy forbid(String policyId, String cedarText,
                                            PolicyDecision effect, PolicyMode mode) {
            return new ResolvedPolicy(policyId, cedarText, true, effect, mode);
        }
    }

    /** 求值失败/超时（M3-05 §1.3：ENFORCE→DENY+审计，DRYRUN→ALLOW+告警）。 */
    public static final class PolicyEvalException extends Exception {
        public PolicyEvalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

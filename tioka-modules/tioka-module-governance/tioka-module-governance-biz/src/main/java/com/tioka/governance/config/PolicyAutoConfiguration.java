package com.tioka.governance.config;

import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.policy.CharterPolicyCompilerApi;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyApi;
import com.tioka.governance.application.policy.CedarSchemaProvider;
import com.tioka.governance.application.policy.DefaultCharterPolicyCompiler;
import com.tioka.governance.application.policy.DefaultPolicyService;
import com.tioka.governance.application.policy.PolicyCache;
import com.tioka.governance.application.policy.PolicyChangeNotifier;
import com.tioka.governance.application.policy.PolicyChangedConsumer;
import com.tioka.governance.application.policy.PolicyEvaluator;
import com.tioka.governance.application.policy.PolicyProperties;
import com.tioka.governance.application.policy.PolicyStore;
import com.tioka.governance.infrastructure.mapper.GovPolicyMapper;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import com.tioka.governance.infrastructure.policy.GovPolicyRepository;
import com.tioka.system.api.audit.AuditApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * GOV-07 / TRU-02 策略引擎装配（M3-05 §1）。默认开启（紧急开关
 * {@code tioka.policy.enabled=false} 关闭求值，切换写审计）。
 */
@AutoConfiguration
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.policy", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PolicyAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "tioka.policy")
    PolicyProperties policyProperties() {
        return new PolicyProperties();
    }

    @Bean
    GovPolicyRepository govPolicyRepository(GovPolicyMapper mapper) {
        return new GovPolicyRepository(mapper);
    }

    @Bean
    PolicyStore policyStore(GovPolicyRepository repository, PolicyProperties props) {
        return new PolicyStore(repository, props.getImportDefaultMode().name());
    }

    @Bean
    CedarSchemaProvider cedarSchemaProvider() {
        return new CedarSchemaProvider(List.of());
    }

    @Bean
    PolicyEvaluator policyEvaluator(CedarSchemaProvider schema) {
        return new PolicyEvaluator(schema);
    }

    @Bean
    PolicyCache policyCache(PolicyStore store, PolicyProperties props) {
        return new PolicyCache(props.getCacheTtlSeconds(), new PolicyCache.Loader() {
            @Override
            public List<PolicyEvaluator.ResolvedPolicy> load(String workspaceId) {
                List<GovPolicyDO> rows = store.loadActiveForEvaluation(workspaceId);
                List<PolicyEvaluator.ResolvedPolicy> out = new java.util.ArrayList<>();
                for (GovPolicyDO row : rows) {
                    boolean forbid = !PolicyStore.SOURCE_SYSTEM.equals(row.getSource())
                            || row.getCedarText() != null && row.getCedarText().contains("forbid");
                    try {
                        out.add(new PolicyEvaluator.ResolvedPolicy(
                                row.getPolicyId(), row.getCedarText(), forbid,
                                com.tioka.governance.api.policy.PolicyDecision.valueOf(row.getEffect()),
                                com.tioka.governance.api.policy.PolicyMode.valueOf(row.getMode())));
                    } catch (RuntimeException ignored) {
                        // 脏行跳过（mode/effect 枚举异常）
                    }
                }
                return out;
            }

            @Override
            public int version(String workspaceId) {
                return store.currentSetVersion(workspaceId);
            }
        });
    }

    @Bean
    PolicyChangeNotifier policyChangeNotifier(ObjectProvider<EventPublisher> publisher) {
        EventPublisher p = publisher.getIfAvailable();
        return p == null
                ? new PolicyChangeNotifier(payload -> { }, true)
                : new PolicyChangeNotifier(p);
    }

    @Bean
    PolicyChangedConsumer policyChangedConsumer(PolicyCache cache) {
        return new PolicyChangedConsumer(cache);
    }

    @Bean
    PolicyApi policyApi(PolicyStore store, PolicyCache cache, PolicyEvaluator evaluator,
                        PolicyChangeNotifier notifier, PolicyProperties props,
                        ObjectProvider<AuditApi> auditApi, PrincipalHolder principals) {
        return new DefaultPolicyService(store, cache, evaluator, notifier, props, auditApi, principals);
    }

    @Bean
    PolicyAdminApi policyAdminApi(PolicyApi policyApi) {
        return (PolicyAdminApi) policyApi;
    }

    @Bean
    CharterPolicyCompilerApi charterPolicyCompiler(PolicyStore store,
                                                   PolicyChangeNotifier notifier) {
        return new DefaultCharterPolicyCompiler(store, notifier, "system");
    }
}

package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import com.tioka.model.ModelTestSupport.InMemoryRoutingTelemetryRepository;
import com.tioka.model.ModelTestSupport.MemorySecretStore;
import com.tioka.model.api.dto.ProviderDtos.CreateAliasRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.RoutingTelemetry;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tioka.model.ModelTestSupport.disabledVersioning;

/**
 * MH-06 离线测试夹具：内存仓储 + 可注入 spec JSON 与假决策器的执行器。
 */
public final class RoutingTestHarness {

    final InMemoryModelEntitiesRepository repo = new InMemoryModelEntitiesRepository();
    final InMemoryRoutingTelemetryRepository telemetryRepo = new InMemoryRoutingTelemetryRepository();
    final ModelEntities entities = new ModelEntities(repo);
    final RoutingTelemetry telemetry = new RoutingTelemetry(telemetryRepo);
    final ObjectMapper mapper = new ObjectMapper();
    final ModelAdminService admin = new ModelAdminService(new MemorySecretStore(), entities,
            Mockito.mock(com.tioka.framework.security.PrincipalHolder.class),
            disabledVersioning(entities));
    final ModelBindingResolver resolver = new ModelBindingResolver(entities, List.of());

    /** 可编程假决策器（id/输出可定制；可注入延迟或异常）。 */
    public static final class FakeDecider implements RoutingDecider {
        private final String id;
        private final Decision decision;
        private final Runnable behavior;

        public FakeDecider(String id, Decision decision) {
            this(id, decision, null);
        }

        public FakeDecider(String id, Decision decision, Runnable behavior) {
            this.id = id;
            this.decision = decision;
            this.behavior = behavior;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Decision decide(DecisionInput input) {
            if (behavior != null) {
                behavior.run();
            }
            return decision;
        }
    }

    /** 固定 spec JSON 的假 spec 源。 */
    public static final class MapSpecSource implements RoutingPlanSpecSource {
        private static final ObjectMapper M = new ObjectMapper();
        private final Map<String, String> specs = new HashMap<>();

        public MapSpecSource put(String planId, String specJson) {
            specs.put(planId, specJson);
            return this;
        }

        @Override
        public java.util.Optional<RoutingPlanSnapshot> load(String planId) {
            String json = specs.get(planId);
            try {
                return json == null ? java.util.Optional.empty()
                        : java.util.Optional.ofNullable(RoutingPlanSpecParser.parse(M.readTree(json)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 注册表把指定 deciderId 全部路由到假决策器（heuristic 兜底默认启发式）。 */
    RoutingPlanExecutor executor(RoutingPlanSpecSource source, String deciderId, RoutingDecider decider) {
        RoutingDeciderRegistry registry = new RoutingDeciderRegistry(
                new HeuristicRoutingDecider(),
                Mockito.mock(LlmRoutingDecider.class),
                Mockito.mock(HttpRoutingDecider.class)) {
            @Override
            public RoutingDecider get(String id) {
                if (id != null && id.equalsIgnoreCase(deciderId)) {
                    return decider;
                }
                if (id == null || "heuristic".equalsIgnoreCase(id)) {
                    return new HeuristicRoutingDecider();
                }
                return null;
            }
        };
        return new RoutingPlanExecutor(resolver, entities, telemetry, source, registry,
                List.of(), Duration.ofMillis(1500));
    }

    String newBinding(String name) {
        var provider = admin.createProvider(new CreateProviderRequest(name + "-p", "mock://openai", "k"));
        String alias = admin.createAlias(new CreateAliasRequest(name + "-m", "tool", null, null, null));
        return admin.createModelBinding(new CreateModelBindingRequest(alias, provider.id(), name));
    }

    String newDisabledBinding(String name) {
        String id = newBinding(name);
        entities.putBinding(new ModelEntities.ModelBinding(id, id + "-lm", id + "-p", name,
                "chat", null, null, 0L, 0L, null, null, false));
        return id;
    }

    ModelCallContext ctx(String threadId, String turnId, String planId) {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, threadId, turnId, null, planId, ModelPurpose.CHAT);
    }

    ModelGateway.ChatRequest chatRequest(String text) {
        return new ModelGateway.ChatRequest(
                List.of(Map.of("role", "user", "content", text)), List.of(), null, null);
    }
}

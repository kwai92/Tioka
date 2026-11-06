package com.tioka.server.gate.support;

import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.AuthService;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Set;

/**
 * 闸门用例：在已启动的 Spring 上下文中播种已发布 AgentVersion（绑定 LLM 桩 baseUrl）。
 */
public final class GateSeed {
    private GateSeed() {}

    public record Bundle(String userId, String workspaceId, String token,
                         String agentVersionId, String routingPlanId) {}

    public static Bundle seedPublishedAgent(ConfigurableApplicationContext ctx, String llmBaseUrl) {
        AuthService auth = ctx.getBean(AuthService.class);
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        AgentService agents = ctx.getBean(AgentService.class);

        AuthService.LoginResult login = auth.login(
                "admin", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        String userId = login.user().id();
        String ws = login.workspaces().isEmpty()
                ? "ws_default"
                : login.workspaces().get(0).id();

        return principals.runAs(user(userId, ws), () -> {
            String tag = Ids.newId("gate");
            var provider = models.createProvider(new CreateProviderRequest(tag + "-llm", llmBaseUrl, "sk-gate"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
            String binding = models.createBinding(new CreateBindingRequest(lm, provider.id(), "gpt-stub"));
            String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(planId);

            AgentStore.Agent agent = agents.create(tag + "-agent", ws);
            AgentStore.AgentVersion draft = agents.draft(agent.id(), "you call echo tools", planId, List.of());
            AgentStore.AgentVersion published = agents.publish(draft.id());
            return new Bundle(userId, ws, login.accessToken(), published.id(), planId);
        });
    }

    public static RuntimeApi.ThreadView createThread(ConfigurableApplicationContext ctx,
                                                     Bundle bundle) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        return principals.runAs(user(bundle.userId(), bundle.workspaceId()), () ->
                runtime.createThread(new RuntimeApi.CreateThreadCmd(
                        bundle.agentVersionId(), "chat", null, "gate", null)));
    }

    public static RuntimeApi.TurnHandle startTurn(ConfigurableApplicationContext ctx,
                                                  Bundle bundle,
                                                  String threadId,
                                                  String clientMsgId,
                                                  String text) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        return principals.runAs(user(bundle.userId(), bundle.workspaceId()), () ->
                runtime.startTurn(threadId, new RuntimeApi.StartTurnCmd(
                        clientMsgId,
                        List.of(RuntimeApi.InputItem.text(text)),
                        null,
                        "chat",
                        null)));
    }

    public static RuntimeApi.TurnView awaitTerminal(ConfigurableApplicationContext ctx,
                                                    Bundle bundle,
                                                    String turnId,
                                                    long timeoutMs) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RuntimeApi.TurnView v = principals.runAs(user(bundle.userId(), bundle.workspaceId()), () ->
                    runtime.getTurn(turnId));
            switch (v.status()) {
                case COMPLETED, FAILED, CANCELLED -> {
                    return v;
                }
                default -> {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new AssertionError("turn 未在 " + timeoutMs + "ms 内结束: " + turnId);
    }

    public static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER,
                Set.of("tenant_admin", "builder"), Set.of("*"), "password");
    }
}

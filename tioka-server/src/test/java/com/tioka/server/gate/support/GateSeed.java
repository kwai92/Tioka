package com.tioka.server.gate.support;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.mcp.application.McpAdminService;
import com.tioka.mcp.domain.McpStore;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.AuthService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闸门用例：在已启动的 Spring 上下文中播种已发布 AgentVersion（绑定 LLM 桩 baseUrl）。
 */
public final class GateSeed {
    private GateSeed() {}

    public record Bundle(String userId, String workspaceId, String token,
                         String agentVersionId, String routingPlanId) {}

    public static Bundle seedPublishedAgent(ConfigurableApplicationContext ctx, String llmBaseUrl) {
        return seedPublishedAgent(ctx, llmBaseUrl, false);
    }

    /**
     * @param withMcp 为 true 时注册 GateCluster MCP 桩、刷新工具、PATCH risk，并把 mcp 绑定写入 AgentSpec。
     */
    public static Bundle seedPublishedAgent(ConfigurableApplicationContext ctx, String llmBaseUrl, boolean withMcp) {
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
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-stub"));
            String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
            models.publish(planId);

            AgentStore.Agent agent = agents.create(tag + "-agent", ws);
            List<AgentSpec.ToolSet.McpBinding> mcp = List.of();
            String instructions = "you call echo tools";
            if (withMcp) {
                McpAdminService mcpAdmin = ctx.getBean(McpAdminService.class);
                Environment env = ctx.getEnvironment();
                String mcpBase = env.getProperty("tioka.gate.mcp-base-url");
                if (mcpBase == null || mcpBase.isBlank()) {
                    throw new IllegalStateException("withMcp 需要 tioka.gate.mcp-base-url");
                }
                McpStore.McpServer server = mcpAdmin.register(tag + "-mcp", mcpBase, "tok");
                mcpAdmin.refreshTools(server.id());
                mcpAdmin.updateToolRisk(server.id(), "erp.query", "READ");
                mcpAdmin.updateToolRisk(server.id(), "erp.update", "WRITE");
                mcp = List.of(new AgentSpec.ToolSet.McpBinding(server.id(),
                        List.of("erp.query", "erp.update", "erp.slow")));
                instructions = "you call mcp tools";
            }

            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(
                            new AgentSpec.Instructions.Section("main", instructions))),
                    new AgentSpec.ModelPolicy(planId, false),
                    new AgentSpec.ToolSet(List.of(), mcp, List.of(), List.of()),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null);
            AgentService.PublishResult published = agents.publishSpec(agent.id(), spec);
            return new Bundle(userId, ws, login.accessToken(), published.versionId(), planId);
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
        return awaitStatus(ctx, bundle, turnId, timeoutMs,
                TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELLED);
    }

    public static RuntimeApi.TurnView awaitStatus(ConfigurableApplicationContext ctx,
                                                  Bundle bundle,
                                                  String turnId,
                                                  long timeoutMs,
                                                  TurnStatus... wanted) {
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        RuntimeApi runtime = ctx.getBean(RuntimeApi.class);
        Set<TurnStatus> want = Set.of(wanted);
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeApi.TurnView last = null;
        while (System.currentTimeMillis() < deadline) {
            last = principals.runAs(user(bundle.userId(), bundle.workspaceId()), () ->
                    runtime.getTurn(turnId));
            if (want.contains(last.status())) {
                return last;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("turn 未在 " + timeoutMs + "ms 内到达 " + want + ": " + turnId
                + " last=" + (last == null ? "null"
                : last.status() + "/" + last.errorCode() + "/" + last.errorMessage()));
    }

    public static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER,
                Set.of("system_admin", "builder"), Set.of("*"), "password");
    }
}

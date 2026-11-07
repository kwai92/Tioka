package com.tioka.server.gate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.dispatch.OrchDispatchService;
import com.tioka.orchestration.infrastructure.mapper.OrchFactMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闸门 G2：ORC-02、ORC-03。双实例双消费者：100 事件 → 100 Run，每 Run 只派一次。
 *
 * <p>回执→SUCCEEDED 完整链路依赖 PK-13；本包断言到 DISPATCHED 与多 worker 不重复领取。
 */
@Tag("gate")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class G2OrchDispatchGateIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.relay-enabled", () -> "false");
        registry.add("tioka.runtime.dispatcher-enabled", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentService agents;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired OrchApi orchApi;
    @Autowired OrchDispatchService dispatchService;
    @Autowired OrchRunMapper runs;
    @Autowired OrchFactMapper facts;

    @Test
    void eventToRunToDispatch() throws Exception {
        Seed seed = seed("g2-one");
        String idem = "g2-one-" + UUID.randomUUID();
        principals.runAs(principal(seed.actor), () -> {
            OrchApi.EventAck ack = orchApi.ingest(
                    new OrchApi.EventEnvelope("feishu", seed.eventType, idem, "subj-g2",
                            Map.of("channel", "feishu", "tioka.addressed", "true"),
                            Map.of("text", "go"), 0));
            OrchApi.EventAck dup = orchApi.ingest(
                    new OrchApi.EventEnvelope("feishu", seed.eventType, idem, "subj-g2",
                            Map.of("channel", "feishu", "tioka.addressed", "true"),
                            Map.of("text", "go"), 0));
            assertEquals(OrchApi.Disposition.DUPLICATE, dup.disposition());

            List<OrchRunDO> runList = runs.selectList(Wrappers.<OrchRunDO>lambdaQuery()
                    .eq(OrchRunDO::getEventId, ack.eventId()));
            assertEquals(1, runList.size());
            String runId = runList.get(0).getId();
            dispatchService.handleRunCreated(runId);
            OrchRunDO after = runs.selectById(runId);
            assertEquals(OrchApi.RunStatus.DISPATCHED.name(), after.getStatus());
            long dispatchedFacts = facts.selectCount(Wrappers.<OrchFactDO>lambdaQuery()
                    .eq(OrchFactDO::getRunId, runId)
                    .eq(OrchFactDO::getKind, "dispatched"));
            assertEquals(1L, dispatchedFacts);
            return null;
        });
    }

    @Test
    void multiWorkerHundredEventsNoDuplicateRunsOrDispatch() throws Exception {
        Seed seed = seed("g2-mw");
        int n = 100;
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> principals.runAs(principal(seed.actor), () -> {
                orchApi.ingest(new OrchApi.EventEnvelope(
                        "feishu", seed.eventType, "mw-" + seed.token + "-" + idx,
                        "subj-mw-" + idx,
                        Map.of("channel", "feishu", "tioka.addressed", "true"),
                        Map.of("i", idx), 0));
                ok.incrementAndGet();
                return null;
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
        assertEquals(n, ok.get());

        List<OrchRunDO> all = principals.runAs(principal(seed.actor), () ->
                runs.selectList(Wrappers.<OrchRunDO>lambdaQuery()
                        .eq(OrchRunDO::getRuleVersionId, seed.versionId)));
        assertEquals(n, all.size(), "100 事件应产生 100 Run");

        // 双 worker 并发派工：每个 Run 投递两次，业务只派一次
        ExecutorService dispatchPool = Executors.newFixedThreadPool(2);
        for (OrchRunDO run : all) {
            String runId = run.getId();
            dispatchPool.submit(() -> principals.runAs(principal(seed.actor), () -> {
                dispatchService.handleRunCreated(runId);
                return null;
            }));
            dispatchPool.submit(() -> principals.runAs(principal(seed.actor), () -> {
                dispatchService.handleRunCreated(runId);
                return null;
            }));
        }
        dispatchPool.shutdown();
        assertTrue(dispatchPool.awaitTermination(180, TimeUnit.SECONDS));

        principals.runAs(principal(seed.actor), () -> {
            for (OrchRunDO run : all) {
                long dispatched = facts.selectCount(Wrappers.<OrchFactDO>lambdaQuery()
                        .eq(OrchFactDO::getRunId, run.getId())
                        .eq(OrchFactDO::getKind, "dispatched"));
                assertEquals(1L, dispatched, "每个 Run 只派一次: " + run.getId());
                OrchRunDO latest = runs.selectById(run.getId());
                assertEquals(OrchApi.RunStatus.DISPATCHED.name(), latest.getStatus());
            }
            return null;
        });
    }

    private Seed seed(String tag) throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        String token = UUID.randomUUID().toString().substring(0, 8);
        String agentVersionId = principals.runAs(principal(actor), () -> publishAgent(actor.workspaceId(), tag + token));
        String eventType = "demo.g2." + tag + "." + token;

        String ruleId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tag + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        String versionId = objectMapper.readTree(mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions", ruleId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"%s",
                                  "conditionCel":"true",
                                  "actions":[{"type":"agent","agentVersionId":"%s","maxSteps":3}],
                                  "concurrency":"parallel",
                                  "acceptMode":"auto",
                                  "serviceAccountId":"%s"
                                }
                                """.formatted(eventType, agentVersionId, actor.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(actor.auth(post("/api/orch/rules/{id}/versions/{vid}/publish", ruleId, versionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STABLE\"}"))
                .andExpect(status().isOk());
        return new Seed(actor, ruleId, versionId, eventType, token);
    }

    private String publishAgent(String workspaceId, String tag) {
        var provider = models.createProvider(new CreateProviderRequest(tag + "-p", "mock://x", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
        String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
        String planId = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
        models.publish(planId);
        var agent = agents.create("orch-g2-" + tag, workspaceId);
        return agents.publishSpec(agent.id(), new AgentSpec(
                1,
                new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "ok"))),
                new AgentSpec.ModelPolicy(planId, false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null)).versionId();
    }

    private static Principal principal(TestFixtures.Actor actor) {
        return new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin", "system_admin"), Set.of("*"), "password");
    }

    private record Seed(TestFixtures.Actor actor, String ruleId, String versionId, String eventType, String token) {}
}

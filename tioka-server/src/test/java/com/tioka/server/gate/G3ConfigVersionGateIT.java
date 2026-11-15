package com.tioka.server.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.PromptRef;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.foundation.common.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigDraft;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.config.ConfigVersionApi.PublishCommand;
import com.tioka.governance.api.config.ConfigVersionApi.RollbackCommand;
import com.tioka.governance.api.config.ConfigVersionApi.SaveDraftCommand;
import com.tioka.model.api.dto.PromptDtos.CreatePromptTemplateRequest;
import com.tioka.model.api.dto.PromptDtos.PromptTemplateView;
import com.tioka.model.api.dto.PromptDtos.RenderResultView;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.prompt.PromptTemplateService;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import com.tioka.system.application.QuotaService;
import com.tioka.system.application.quota.QuotaMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 G3 闸门（GOV-12 / MH-08〜10 / AST-07 转真，ADR-041 §10 四步用例）。
 *
 * <p>规格：ADR-041 §10（L298-304）四步用例 ——
 * ① 发布 binding 第 2 版把地址指向会报错的桩，错误率上升；
 * ② 回滚到第 1 版后调用恢复，统计里两个版本的调用分开可查；
 * ③ 供应商 A 月度预算用尽 → 路由跳到 B 并留下「因预算跳过」；
 * ④ Agent 钉住的 Prompt 模板发新版后，已发布 Agent 仍用旧版本，重发布才用新版。
 * 覆盖范围见 {@code docs/architecture/construction/M3-00-M3实施计划.md}（GOV-12、MH-08〜10、GOV-13、AST-07）。
 *
 * <p><b>环境与接线口径（偏差逐条登记在设计说明 M3-C30；仅测试侧适配，未改生产代码）：</b>
 * <ol>
 *   <li><b>步骤 ①②的承载通路改走路由计划版本内容</b>：{@code DefaultConfigVersionService.applyCurrent}
 *       与 {@code CfgProjectionPointerMapper}（V150 只加 {@code current_version_id/no} 两列）在发布/回滚时
 *       只回填投影行指针列，<em>不回写</em> {@code model_provider}/{@code model_binding} 业务列；且
 *       {@code UpdateModelBindingRequest} 无 {@code providerId}，binding 版本改不了地址。
 *       故 ADR 原文的「发布 binding 第 2 版改地址」改由「发布<em>路由计划</em>第 2 版，其 {@code main}
 *       组指向故障目标」承载——{@code VersionedRoutingPlanSpecSource} 是当前唯一在运行期真正读版本内容的通路
 *       （{@code RoutingPlanSpecParser} 见到 {@code mode} 字段才交给 {@code RoutingPlanExecutor}），
 *       回滚到第 1 版即恢复既有平铺选模行为。binding 版本仅用于用量分片断言。</li>
 *   <li><b>「会报错的桩」以不可达地址承载</b>：{@code LlmStubServer} 按请求头 {@code X-Tioka-Script}
 *       选剧本，而该头由 {@code ProviderClient} 取自全局 {@code System.getProperty("tioka.llm.script")}
 *       （同集群所有 binding 共用同一剧本名），无法让单个 binding 各自换剧本；故故障目标用
 *       provider baseUrl 指向不可达端口（{@code tioka.egress.mode=OPEN} 下必然连接失败）来制造确定性错误。</li>
 *   <li><b>「供应商 DOWN」告警不在此断言</b>：MH-10 健康信号源 {@code AlertSignalProvider} 尚未实现
 *       （唯一实现是结构化输出的 {@code StructuredOutputAlertProvider}），{@code AlertEvaluator} 无健康事件源。
 *       步骤 ①「错误率上升」改为断言其可观测后果：调用以模型类错误收口 + {@code model_usage} 留下非成功记录。</li>
 *   <li><b>步骤 ④ 只断言钉版本事实</b>：{@code PromptRef} 目前仅作为 AgentSpec 契约字段落库（schema 校验通过），
 *       运行期的注入/渲染接线未实现；故断言已发布 {@code agent_version.spec_json} 的
 *       {@code prompt.pinnedVersion} 不随模板发新版漂移，重发布 Agent 后才取新版。</li>
 *   <li>本机 Docker 不可用：闸门端到端结论归 CI（{@code -Pgate}）。</li>
 * </ol>
 */
@Tag("gate")
class G3ConfigVersionGateIT {

    private static final String SCRIPT = "cfg-gate";
    private static final String OK_CONTENT = "cfg-ok";
    /** 会报错的桩：不可达端口（EGRESS=OPEN 下必然连接失败，用于制造确定性失败）。 */
    private static final String DEAD_BASE_URL = "http://127.0.0.1:1/v1";
    private static final long REFRESH_TIMEOUT_MS = 20_000;

    /**
     * ADR-041 §10 四步用例（同集群双实例，共享 PG/Redis 与 LLM 桩）。
     *
     * <p>闸门走真实运行期选模（{@code RoutingPlanExecutor} / {@code ModelBindingResolver}）与真实网关
     * （重试、故障接力、用量台账），只把 LLM 换成录制回放桩；版本框架经
     * {@code tioka.config.version.enabled=true} 真实装配（{@code GateCluster.baseProps} 默认不含该键）。
     */
    @Test
    void configVersionPublishRollbackBudgetAndPromptPinning() {
        Map<String, Object> props = Map.of(
                "tioka.config.version.enabled", "true",
                "tioka.model.retry.base-delay", "1ms",
                "tioka.model.retry.budget", "5s");
        try (GateCluster cluster = GateCluster.start(2, SCRIPT, props, false)) {
            ConfigurableApplicationContext ctxA = cluster.a();
            ConfigurableApplicationContext ctxB = cluster.b();
            JdbcTemplate jdbc = ctxA.getBean(JdbcTemplate.class);
            ObjectMapper mapper = ctxA.getBean(ObjectMapper.class);
            PrincipalHolder principals = ctxA.getBean(PrincipalHolder.class);

            // 桩剧本：所有 binding 共用（见类注释偏差 2）
            cluster.llm().putScript(SCRIPT, okScript());

            GateSeed.Bundle base = GateSeed.seedPublishedAgent(ctxA, cluster.llm().baseUrl(), false, null);
            principals.runAs(principal(base), () -> null);
            Principal p = principal(base);
            ModelAdminService models = ctxA.getBean(ModelAdminService.class);
            ModelEntities entities = ctxA.getBean(ModelEntities.class);
            ConfigVersionApi versionsA = ctxA.getBean(ConfigVersionApi.class);

            String planId = base.routingPlanId();
            String healthyBinding = entities.getPlan(planId).orElseThrow().primaryBindingId();
            assertNotNull(healthyBinding, "播种计划的 primary binding 必填");

            // 版本框架随开关真实装配（否则后续发布校验/指针回填全部空转）
            assertTrue(ctxA.getBean(ModelConfigVersionService.class).available(),
                    "tioka.config.version.enabled=true 时版本框架须已装配");

            // ---------- 步骤 ①：发布第 2 版（指向故障目标）→ 调用失败、错误率上升 ----------
            Pair dead = principals.runAs(p, () -> newBinding(ctxA, DEAD_BASE_URL));
            String deadBinding = dead.bindingId();

            ObjectRef planRef = new ObjectRef(ModelHubSpecs.OT_ROUTING_PLAN, planId);
            // V2：MH-06 结构（含 mode）→ 运行期由 RoutingPlanExecutor 接管；main 组指向故障目标
            ObjectNode v2 = mapper.createObjectNode();
            v2.put("mode", "FIXED");
            v2.putObject("groups").putArray("main").add(deadBinding);
            assertEquals(1, currentVersionNo(jdbc, planId), "播种后投影行指针应为第 1 版");

            long rev = versionsA.getDraft(planRef).map(ConfigDraft::revision).orElse(0L);
            versionsA.saveDraft(new SaveDraftCommand(planRef, v2, rev));
            ConfigVersion published = versionsA.publish(new PublishCommand(planRef, "G3 步骤1：V2 指向故障目标"));
            assertEquals(2, published.versionNo(), "发布须产生第 2 版");

            // 跨实例刷新（GOV-12）：A 发布后 B 的投影须跟上；DB 指针列同步
            assertEquals(2, awaitVersion(ctxB, planRef, 2), "B 实例须刷新到第 2 版");
            assertEquals(2, currentVersionNo(jdbc, planId), "投影行指针列须回填第 2 版");

            String failThread = Ids.newId("sess");
            String failTurn = Ids.newId("turn");
            BizException ex = assertThrows(BizException.class,
                    () -> chat(ctxA, p, planId, failThread, failTurn),
                    "第 2 版指向故障目标时调用必须失败（错误率上升）");
            assertTrue(Set.of(ErrorCode.MODEL_UNAVAILABLE, ErrorCode.MODEL_ERROR).contains(ex.getErrorCode()),
                    () -> "故障版本调用须以模型类错误收口，实测=" + ex.getErrorCode() + " / " + ex.getMessage());
            System.out.println("[g3 步骤1] 故障版本错误码=" + ex.getErrorCode()
                    + "，用量台账非成功记录数=" + failedUsageCount(jdbc, failTurn));
            assertTrue(failedUsageCount(jdbc, failTurn) > 0,
                    "故障版本调用须在 model_usage 留下非成功记录（错误率可观测）");
            dump(jdbc, failThread, failTurn);

            // ---------- 步骤 ②：回滚到第 1 版 → 调用恢复；两个版本的用量分开可查 ----------
            ConfigVersion rolled = versionsA.rollback(new RollbackCommand(planRef, 1, "G3 步骤2：回滚到 V1"));
            assertEquals(1, rolled.versionNo(), "回滚不产生新版本号，须回到第 1 版");
            assertEquals(1, awaitVersion(ctxB, planRef, 1), "B 实例须刷新回第 1 版");
            assertEquals(1, currentVersionNo(jdbc, planId), "投影行指针列须回到第 1 版");

            String okThread = Ids.newId("sess");
            String okTurn = Ids.newId("turn");
            ModelGateway.ChatResult recovered = chat(ctxA, p, planId, okThread, okTurn);
            assertEquals(OK_CONTENT, recovered.content(), "回滚后调用须恢复");
            assertEquals(healthyBinding, recovered.bindingId(),
                    "回滚后须回到第 1 版的平铺选模路径（primary binding）");

            // 用量按版本分片（MH-08：plan_version_no，V222 列）
            assertEquals(List.of(2), usageVersions(jdbc, failTurn), "第 2 版调用须记 plan_version_no=2");
            assertEquals(List.of(1), usageVersions(jdbc, okTurn), "第 1 版调用须记 plan_version_no=1");
            System.out.println("[g3 步骤2] 用量版本分片可查：V2=" + usageVersions(jdbc, failTurn)
                    + " V1=" + usageVersions(jdbc, okTurn));

            // ---------- 步骤 ③：供应商 A 月度预算用尽 → 路由跳到 B + 「因预算跳过」留痕 ----------
            String budgetTurn;
            String budgetThread;
            Pair primary = principals.runAs(p, () -> newBinding(ctxA, cluster.llm().baseUrl()));
            Pair fallback = principals.runAs(p, () -> newBinding(ctxA, cluster.llm().baseUrl()));
            String budgetPlan = principals.runAs(p, () -> {
                String id = models.createPlan(new CreateRoutingPlanRequest(
                        Ids.newId("rp"), "PRIMARY_FALLBACK", primary.bindingId(), fallback.bindingId(), null)).id();
                models.publish(id);
                return id;
            });
            // 供应商 A 月度成本上限置 0 → 已用(0) >= 上限(0) 即耗尽（MH-09）
            principals.runAs(p, () -> {
                ctxA.getBean(QuotaService.class).putLimit(QuotaMetrics.SCOPE_PROVIDER, primary.providerId(),
                        QuotaMetrics.COST_MONTH, 0L, null);
                return null;
            });

            budgetThread = Ids.newId("sess");
            budgetTurn = Ids.newId("turn");
            ModelGateway.ChatResult skipped = chat(ctxA, p, budgetPlan, budgetThread, budgetTurn);
            assertEquals(fallback.bindingId(), skipped.bindingId(),
                    "供应商 A 预算耗尽后须跳到备用 binding B");
            assertEquals(OK_CONTENT, skipped.content(), "备用调用须成功");

            Map<String, Object> freeze = jdbc.queryForMap(
                    "SELECT binding_id, reason, failover_count FROM model_turn_freeze"
                            + " WHERE thread_id = ? AND turn_id = ?", budgetThread, budgetTurn);
            assertEquals(fallback.bindingId(), freeze.get("binding_id"), "冻结 binding 须切到备用");
            assertEquals("budget-exhausted", freeze.get("reason"), "须留下「因预算跳过」的接力原因");
            assertEquals(1, ((Number) freeze.get("failover_count")).intValue(), "须恰好一次预算接力");
            Set<String> statuses = Set.copyOf(jdbc.queryForList(
                    "SELECT status FROM model_usage WHERE turn_id = ?", String.class, budgetTurn));
            assertTrue(statuses.contains("FAILOVER") && statuses.contains("SUCCESS"),
                    () -> "用量台账须同时留预算跳过与成功记录，实测=" + statuses);
            System.out.println("[g3 步骤3] 预算接力留痕=" + freeze + " 用量状态=" + statuses);

            // ---------- 步骤 ④：Prompt 模板钉版本 ----------
            PromptTemplateService prompts = ctxA.getBean(PromptTemplateService.class);
            AgentService agents = ctxA.getBean(AgentService.class);
            ObjectNode tpl = template(mapper, "sys." + Ids.newId("g3prompt"), "你是助手，请回答：{{question}}");
            PromptTemplateView created = principals.runAs(p,
                    () -> prompts.create(new CreatePromptTemplateRequest(tpl, true)));
            assertEquals(1, created.currentVersionNo(), "创建即发布第 1 版");

            AgentStore.Agent agent = principals.runAs(p, () -> agents.create("g3-pin-" + Ids.newId("a"), base.workspaceId()));
            AgentService.PublishResult v1 = principals.runAs(p,
                    () -> agents.publishSpec(agent.id(), pinnedSpec(planId, new PromptRef(created.id(), 1))));
            assertEquals(1, pinnedVersion(jdbc, mapper, v1.versionId()), "Agent 第 1 版钉模板第 1 版");

            // 模板发第 2 版（Agent 侧不动）
            ObjectRef tplRef = new ObjectRef(ModelHubSpecs.OT_PROMPT_TEMPLATE, created.id());
            ObjectNode tpl2 = template(mapper, created.name(), "你是助手（v2），请回答：{{question}}");
            long tplRev = versionsA.getDraft(tplRef).map(ConfigDraft::revision).orElse(0L);
            prompts.saveDraft(created.id(), tpl2, tplRev);
            ctxA.getBean(ModelConfigVersionService.class)
                    .publishDraft(ModelHubSpecs.OT_PROMPT_TEMPLATE, created.id(), "G3 步骤4：模板第 2 版");
            assertEquals(2, prompts.get(created.id()).currentVersionNo(), "模板当前生效版本须为第 2 版");

            // 钉版本语义：模板发新版不改变已发布 AgentVersion 的 pin
            assertEquals(1, pinnedVersion(jdbc, mapper, v1.versionId()),
                    "模板发第 2 版后，已发布 AgentVersion 仍钉第 1 版");
            RenderResultView rendered = prompts.render(created.id(), Map.of("question", "你好"));
            assertTrue(rendered.sections().get(0).body().contains("v2"),
                    "模板当前生效版本确为第 2 版（渲染取当前版本）: " + rendered.sections());

            // 重发布 Agent 后才取新版
            AgentService.PublishResult agentV2 = principals.runAs(p,
                    () -> agents.publishSpec(agent.id(), pinnedSpec(planId, new PromptRef(created.id(), 2))));
            assertEquals(2, pinnedVersion(jdbc, mapper, agentV2.versionId()), "重发布 Agent 后须钉第 2 版");
            System.out.println("[g3 步骤4] Agent 钉版本：v1=" + pinnedVersion(jdbc, mapper, v1.versionId())
                    + " v2=" + pinnedVersion(jdbc, mapper, agentV2.versionId()));
        }
    }

    // ------------------------------------------------------------------
    // 选模 / 调用
    // ------------------------------------------------------------------

    private static Principal principal(GateSeed.Bundle bundle) {
        return GateSeed.user(bundle.userId(), bundle.workspaceId());
    }

    private static ModelGateway.ChatResult chat(ConfigurableApplicationContext ctx, Principal p,
                                                String planId, String threadId, String turnId) {
        ModelGateway gateway = ctx.getBean(ModelGateway.class);
        PrincipalHolder principals = ctx.getBean(PrincipalHolder.class);
        return principals.runAs(p, () -> gateway.chat(
                new ModelCallContext(p, threadId, turnId, null, planId, ModelPurpose.CHAT),
                new ModelGateway.ChatRequest(
                        List.of(Map.of("role", "user", "content", "ping")),
                        List.of(), null, null),
                d -> { }));
    }

    /** 建一对「供应商 + binding」（同 provider，用于按供应商维度设预算）。 */
    private static Pair newBinding(ConfigurableApplicationContext ctx, String baseUrl) {
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        String providerId = models.createProvider(
                new CreateProviderRequest(Ids.newId("p"), baseUrl, "sk-gate")).id();
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
        String bindingId = models.createModelBinding(new CreateModelBindingRequest(lm, providerId, "gpt-stub"));
        return new Pair(providerId, bindingId);
    }

    private record Pair(String providerId, String bindingId) {}

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    /** 跨实例刷新：轮询 B 实例的本地投影直到目标版本（GOV-12 发布/回滚的跨实例传播）。 */
    private static int awaitVersion(ConfigurableApplicationContext ctx, ObjectRef ref, int expected) {
        ConfigVersionApi api = ctx.getBean(ConfigVersionApi.class);
        long deadline = System.currentTimeMillis() + REFRESH_TIMEOUT_MS;
        ConfigVersion last = null;
        while (System.currentTimeMillis() < deadline) {
            last = api.getCurrent(ref).orElse(null);
            if (last != null && last.versionNo() == expected) {
                return expected;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("实例未在 " + REFRESH_TIMEOUT_MS + "ms 内刷新到版本 " + expected
                + "，实测=" + (last == null ? "null" : last.versionNo()));
    }

    private static Integer currentVersionNo(JdbcTemplate jdbc, String planId) {
        return jdbc.queryForObject(
                "SELECT current_version_no FROM model_routing_plan WHERE id = ?", Integer.class, planId);
    }

    private static int failedUsageCount(JdbcTemplate jdbc, String turnId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_usage WHERE turn_id = ? AND status IS DISTINCT FROM 'SUCCESS'",
                Integer.class, turnId);
        return n == null ? 0 : n;
    }

    private static List<Integer> usageVersions(JdbcTemplate jdbc, String turnId) {
        return jdbc.queryForList(
                "SELECT DISTINCT plan_version_no FROM model_usage WHERE turn_id = ?", Integer.class, turnId);
    }

    private static int pinnedVersion(JdbcTemplate jdbc, ObjectMapper mapper, String agentVersionId) {
        String json = jdbc.queryForObject(
                "SELECT spec_json FROM agent_version WHERE id = ?", String.class, agentVersionId);
        try {
            return mapper.readTree(json).path("prompt").path("pinnedVersion").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("解析 agent_version.spec_json 失败: " + e.getMessage(), e);
        }
    }

    /** 失败诊断：把该 Turn 的用量与冻结行打到标准输出（不参与断言）。 */
    private static void dump(JdbcTemplate jdbc, String threadId, String turnId) {
        System.out.println("[g3-diag] model_usage=" + jdbc.queryForList(
                "SELECT binding_id, status, plan_version_no, binding_version_no FROM model_usage WHERE turn_id = ?",
                turnId));
        System.out.println("[g3-diag] model_turn_freeze=" + jdbc.queryForList(
                "SELECT binding_id, reason, failover_count FROM model_turn_freeze WHERE thread_id = ? AND turn_id = ?",
                threadId, turnId));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** Prompt 模板 spec 夹具（system 作用域须以 sys. 开头；占位符与变量双向一致 + 样例可渲染）。 */
    private static ObjectNode template(ObjectMapper mapper, String name, String body) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", name);
        n.put("scope", "system");
        n.put("purpose", "agent");
        ArrayNode sections = n.putArray("sections");
        sections.addObject().put("role", "system").put("body", body);
        ArrayNode vars = n.putArray("variables");
        vars.addObject().put("name", "question").put("type", "string").put("required", true)
                .put("description", "用户问题");
        n.put("outputFormat", "text");
        n.putObject("sampleVariables").put("question", "你好");
        return n;
    }

    /** 18 参兼容构造（datasets 缺省空）；prompt 为尾 8 参第 3 位（index 12）。 */
    private static AgentSpec pinnedSpec(String planId, PromptRef prompt) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile("g3-pin", null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(
                        new AgentSpec.Instructions.Section("main", "answer"))),
                new AgentSpec.ModelPolicy(planId, false),
                new AgentSpec.ToolSet(List.of(), List.of(), List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, prompt, null, null, null, null, null);
    }

    private static LlmStubServer.Script okScript() {
        LlmStubServer.Script s = new LlmStubServer.Script();
        LlmStubServer.Call c = new LlmStubServer.Call();
        c.content = OK_CONTENT;
        LlmStubServer.UsageSpec u = new LlmStubServer.UsageSpec();
        u.prompt = 10;
        u.completion = 5;
        c.usage = u;
        s.calls = List.of(c);
        s.loop = Boolean.TRUE;
        return s;
    }
}

package com.tioka.server.gate;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.DatasetResult;
import com.tioka.connector.application.dataset.DatasetGateway;
import com.tioka.connector.application.dataset.DatasetPolicyModel;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.routing.PlanLabelGate;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.MandateScope;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.application.grant.GrantAgentViews;
import com.tioka.runtime.application.label.TurnLabelTracker;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import com.tioka.server.gate.support.LlmStubServer;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.auth.PrincipalLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门 G3：数据集策略 + 数据标签链路（M3-02「闸门 G3 追加用例」L186，批次 C-17）。
 *
 * <p>权威断言（docs/architecture/construction/M3-02-数据集策略与数据标签.md）：
 * 两位不同区域用户经同一 Agent 查同一数据集，得到不同行与掩码；子 Agent 读到 CONFIDENTIAL 后，
 * 结果不能原文发到外部群，下一次模型调用只在获准计划内选模。
 *
 * <p>实现口径（DAT-01〜03 生产设施，无 mock；空间随机前缀隔离）：
 * <ul>
 *   <li>①双区域差异：区域身份经两条真实路径承载——行差异走授予 scope（生产既定口径：
 *       DataQueryToolProvider 的 principal.attrs 未接线，M3 行谓词 attrs 用例经授予 scope 键
 *       AND 合并承载）；掩码差异经 DatasetGateway 直调（PrincipalSnapshot.attrs 注入
 *       Keycloak userRegionAttribute 的 region 属性）。两条路径都用同一数据集、同一策略、
 *       真实 JDBC 源（共享 PG 内真实表）；</li>
 *   <li>②真实对话轮：Agent 经 data.query 工具（唯一取数入口）→ DatasetGateway
 *       （ACL→授予→策略→JDBC 执行→掩码→打标→审计）→ 结果标签 CONFIDENTIAL 经
 *       TurnLabelTracker 抬升 runtime_thread.data_label（MONOTONIC）；</li>
 *   <li>③去向：播种 gov_label_sink 空间配置（IM/external-group max=INTERNAL）→
 *       SinkGuard DENY；预演管线 egress-permit 步（SinkGuardPipelineStep）→
 *       DATA_LABEL_EXCEEDED(40332) + tool.invoke DENIED 审计；</li>
 *   <li>④选模：Turn 标签 CONFIDENTIAL → PlanLabelGate/MODEL_PLAN 通道——
 *       未播种许可行时 assertPlanAllowed 抛 NO_ELIGIBLE_MODEL(42210)；
 *       播种获准行（max_label=CONFIDENTIAL）后放行。真实 Turn 级断言不可行：
 *       ModelAdminService.createPlan 只产 V1 平铺 spec（无 mode 字段）→
 *       RoutingPlanExecutor 对 legacy 快照回退既有路径，plan 闸不进 Turn 链（登记偏离）；
 *       闸门核心链（标签→路由闸）经真实 PlanLabelGate + 真实标签库 + 真实 gov_label_sink 行保持。</li>
 * </ul>
 */
@Tag("gate")
class G3DataPermissionGateIT {

    @Test
    void dualRegionRowMaskDifferAndLabelChainPlanGate() throws Exception {
        // 随机前缀空间隔离（C-5：共享 PG 无 TRUNCATE，workspace/数据集名均随机）
        String ws = "ws_g3dat_" + UUID.randomUUID().toString().substring(0, 8);
        String table = "g3ds_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
        String datasetName = "contracts-" + UUID.randomUUID().toString().substring(0, 8);
        String tag = Ids.newId("g3dat");
        String dsetId = "g3ds_" + UUID.randomUUID().toString().substring(0, 12);
        String script = "g3-dataperm";

        try (GateCluster cluster = GateCluster.start(1, script, Map.of(), true)) {
            // —— 1. 双区域用户（真实 Keycloak 身份 + region 属性）+ 随机空间成员 ——
            cluster.keycloak().ensureRegionUsers();
            new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0))
                    .login(KeycloakGateSupport.USER_EAST, KeycloakGateSupport.PASSWORD_EAST);
            new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0))
                    .login(KeycloakGateSupport.USER_SOUTH, KeycloakGateSupport.PASSWORD_SOUTH);

            JdbcTemplate jdbc = cluster.a().getBean(JdbcTemplate.class);
            Long eastUid = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, KeycloakGateSupport.USER_EAST);
            Long southUid = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, KeycloakGateSupport.USER_SOUTH);
            assertNotNull(eastUid);
            assertNotNull(southUid);
            jdbc.update("""
                    INSERT INTO sys_workspace (id, name, owner_user_id, create_at, update_at, create_by, update_by, deleted)
                    VALUES (?, 'G3DataPerm', 1, now(), now(), 'system', 'system', FALSE)
                    """, ws);
            // 播种模型/Agent 发布需 workspace_admin（member 缺 model:read / agent:write）
            jdbc.update("INSERT INTO sys_member (workspace_id, user_id, role_code) VALUES (?, ?, 'workspace_admin')",
                    ws, String.valueOf(eastUid));
            jdbc.update("INSERT INTO sys_member (workspace_id, user_id, role_code) VALUES (?, ?, 'workspace_admin')",
                    ws, String.valueOf(southUid));
            String eastRegion = cluster.keycloak().userRegionAttribute(KeycloakGateSupport.USER_EAST);
            String southRegion = cluster.keycloak().userRegionAttribute(KeycloakGateSupport.USER_SOUTH);
            assertEquals(KeycloakGateSupport.REGION_EAST, eastRegion);
            assertEquals(KeycloakGateSupport.REGION_SOUTH, southRegion);

            PrincipalLoader loader = cluster.a().getBean(PrincipalLoader.class);
            Principal east = loader.load(eastUid, ws);
            Principal south = loader.load(southUid, ws);
            PrincipalHolder principals = cluster.a().getBean(PrincipalHolder.class);

            // —— 2. 模型 + Agent（data.query 为内置唯一取数工具）——
            // 播种须经 Principal 上下文（agents.create/@Audited 读 principals.current()）
            ModelAdminService models = cluster.a().getBean(ModelAdminService.class);
            record ModelSeed(String planId, String agentId, String versionId) {}
            ModelSeed seed = principals.runAs(east, () -> {
                var provider = models.createProvider(new CreateProviderRequest(tag + "-llm", cluster.llm().baseUrl(), "sk-g3"));
                String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "tool"));
                String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-stub"));
                String plan = models.createPlan(new CreateRoutingPlanRequest(tag + "-rp", "FIXED", binding)).id();
                models.publish(plan);

                AgentService agents = cluster.a().getBean(AgentService.class);
                AgentStore.Agent agent = agents.create(tag + "-agent", ws);
                AgentSpec spec = new AgentSpec(1,
                        new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                        new AgentSpec.Instructions(List.of(
                                new AgentSpec.Instructions.Section("main", "查询合同数据集并汇总"))),
                        new AgentSpec.ModelPolicy(plan, false),
                        AgentSpec.ToolSet.empty(),
                        List.of(),
                        AgentSpec.KnowledgeBinding.empty(),
                        AgentSpec.SandboxPolicy.defaults(),
                        AgentSpec.ApprovalSettings.empty(),
                        AgentSpec.Limits.defaults(),
                        null, null, null, null, null, null, null, null);
                AgentService.PublishResult published = agents.publishSpec(agent.id(), spec);
                return new ModelSeed(plan, agent.id(), published.versionId());
            });

            // —— 3. 真实 JDBC 数据表 + 数据集登记 + ENFORCE 行列策略 ——
            jdbc.execute("CREATE TABLE " + table + " ("
                    + "contract_id VARCHAR(32), region VARCHAR(32), phone VARCHAR(32), note VARCHAR(64))");
            jdbc.update("INSERT INTO " + table + " VALUES ('C1', ?, '13812345678', '华东合同甲')",
                    KeycloakGateSupport.REGION_EAST);
            jdbc.update("INSERT INTO " + table + " VALUES ('C2', ?, '13900001111', '华东合同乙')",
                    KeycloakGateSupport.REGION_EAST);
            jdbc.update("INSERT INTO " + table + " VALUES ('C3', ?, '18612345678', '华南合同丙')",
                    KeycloakGateSupport.REGION_SOUTH);
            jdbc.update("INSERT INTO " + table + " VALUES ('C4', ?, '18700002222', '华南合同丁')",
                    KeycloakGateSupport.REGION_SOUTH);

            jdbc.update("""
                    INSERT INTO data_dataset (id, workspace_id, connector_id, name, source_json, columns_json, owner_id, status)
                    VALUES (?, ?, 'conn-g3', ?, ?, ?, '1', 'ACTIVE')
                    """, dsetId, ws, datasetName,
                    "{\"kind\":\"jdbc\",\"sql\":\"SELECT * FROM " + table + "\"}",
                    "[{\"name\":\"contract_id\",\"type\":\"varchar\",\"label\":\"INTERNAL\",\"defaultMask\":\"NONE\"},"
                            + "{\"name\":\"region\",\"type\":\"varchar\",\"label\":\"INTERNAL\",\"defaultMask\":\"NONE\"},"
                            + "{\"name\":\"phone\",\"type\":\"varchar\",\"label\":\"CONFIDENTIAL\",\"defaultMask\":\"PARTIAL\"},"
                            + "{\"name\":\"note\",\"type\":\"varchar\",\"label\":\"INTERNAL\",\"defaultMask\":\"NONE\"}]");
            jdbc.update("""
                    INSERT INTO data_dataset_policy (dataset_id, version, rows_json, cols_json, mode, status, created_by, created_at)
                    VALUES (?, 1, ?, ?, 'ENFORCE', 'ACTIVE', '1', now())
                    """, dsetId,
                    "[{\"predicate\":\"row.region in ['" + KeycloakGateSupport.REGION_EAST + "','"
                            + KeycloakGateSupport.REGION_SOUTH + "']\"}]",
                    "[{\"column\":\"phone\",\"when\":\"principal.attrs.region == '" + KeycloakGateSupport.REGION_EAST
                            + "'\",\"mask\":\"NONE\"}]");

            // —— 4. 区域授予（生产路径 GrantService.root + MandateScope；scope 键=列名 region）——
            // 快照/Agent 行读取须 Principal 上下文（agent 表非 scope-exempt）
            GrantService grants = cluster.a().getBean(GrantService.class);
            ToolRegistry toolRegistry = cluster.a().getBean(ToolRegistry.class);
            var view = principals.runAs(east, () -> {
                var snap = cluster.a().getBean(com.tioka.agent.api.AgentSnapshotApi.class).get(seed.versionId());
                return GrantAgentViews.fromSnapshot(snap, toolRegistry);
            });
            DelegationGrant eastGrant = grants.root(east, null, view,
                    new MandateScope(Map.of("region", List.of(KeycloakGateSupport.REGION_EAST))));
            DelegationGrant southGrant = grants.root(south, null, view,
                    new MandateScope(Map.of("region", List.of(KeycloakGateSupport.REGION_SOUTH))));

            // —— 5. ① 双区域行与掩码差异（同 Agent/同数据集/同策略；直调 DatasetGateway）——
            // DatasetGateway 读 data_dataset（非 scope-exempt）须 Principal 上下文
            DatasetGateway gateway = cluster.a().getBean(DatasetGateway.class);
            DatasetResult eastResult = principals.runAs(east, () -> gateway.query(ws, datasetName,
                    new DatasetQuery(null, List.of(), 100),
                    new DatasetPolicyModel.PrincipalSnapshot(String.valueOf(eastUid),
                            List.copyOf(east.roles()), Map.of("region", eastRegion)),
                    grants.get(eastGrant.grantId()).scope()));
            DatasetResult southResult = principals.runAs(south, () -> gateway.query(ws, datasetName,
                    new DatasetQuery(null, List.of(), 100),
                    new DatasetPolicyModel.PrincipalSnapshot(String.valueOf(southUid),
                            List.copyOf(south.roles()), Map.of("region", southRegion)),
                    grants.get(southGrant.grantId()).scope()));
            // 行不同：华东只见 C1/C2，华南只见 C3/C4
            assertEquals(List.of("C1", "C2"),
                    eastResult.rows().stream().map(r -> r.get("contract_id")).toList(),
                    "华东用户行集");
            assertEquals(List.of("C3", "C4"),
                    southResult.rows().stream().map(r -> r.get("contract_id")).toList(),
                    "华南用户行集");
            // 掩码不同：华东 phone 原文，华南 phone PARTIAL 掩码
            assertEquals("13812345678", eastResult.rows().get(0).get("phone"), "华东 phone 应不掩码");
            assertEquals("186****5678", southResult.rows().get(0).get("phone"), "华南 phone 应 PARTIAL 掩码");
            assertTrue(eastResult.maskedColumns().isEmpty(), "华东无掩码列");
            assertTrue(southResult.maskedColumns().contains("phone"), "华南掩码列应含 phone");
            // 结果标签 = 列密级最高值，从不自动下降
            assertEquals(com.tioka.framework.label.DataLabel.CONFIDENTIAL, eastResult.label());
            assertEquals(com.tioka.framework.label.DataLabel.CONFIDENTIAL, southResult.label());

            // —— 6. ② 真实对话轮：data.query → 行随授予 scope 过滤 + 线程标签抬升 ——
            LlmStubServer.Script llmScript = new LlmStubServer.Script();
            llmScript.loop = false;
            LlmStubServer.Call queryCall = new LlmStubServer.Call();
            LlmStubServer.ToolCallSpec qc = new LlmStubServer.ToolCallSpec();
            qc.name = "data.query";
            qc.arguments = Map.of("dataset", datasetName, "limit", 50);
            queryCall.tool_calls = List.of(qc);
            LlmStubServer.Call answerCall = new LlmStubServer.Call();
            answerCall.content = "已汇总合同数据";
            llmScript.calls = List.of(queryCall, answerCall);
            cluster.llm().putScript(script, llmScript);

            RuntimeApi runtime = cluster.a().getBean(RuntimeApi.class);
            RuntimeApi.ThreadView thread = principals.runAs(east, () ->
                    runtime.createThread(new RuntimeApi.CreateThreadCmd(
                            seed.versionId(), "chat", null, "g3-dataperm", null)));
            // 预置区域授予：ensureRootGrant 见 ACTIVE 授予即复用（不重签）
            jdbc.update("UPDATE runtime_thread SET grant_id = ? WHERE id = ?",
                    eastGrant.grantId(), thread.threadId());
            RuntimeApi.TurnHandle handle = principals.runAs(east, () ->
                    runtime.startTurn(thread.threadId(), new RuntimeApi.StartTurnCmd(
                            "cm-g3-dat-1",
                            List.of(RuntimeApi.InputItem.text("查询合同数据集")),
                            null, "chat", null)));
            GateSeed.Bundle bundle = new GateSeed.Bundle(
                    String.valueOf(eastUid), ws, null, seed.versionId(), seed.planId());
            RuntimeApi.TurnView turn = GateSeed.awaitTerminal(cluster.a(), bundle, handle.turnId(), 120_000);
            assertEquals(TurnStatus.COMPLETED, turn.status(), () -> turn.errorMessage());

            // runtime_thread 非 scope-exempt，标签读须 Principal 上下文
            try {
                assertEquals(com.tioka.framework.label.DataLabel.CONFIDENTIAL,
                        principals.runAs(east, () -> cluster.a().getBean(TurnLabelTracker.class)
                                .current(thread.threadId())),
                        "data.query 结果标签应抬升 thread 标签（MONOTONIC）");
            } catch (AssertionError e) {
                jdbc.queryForList(
                        "SELECT type, seq, data_label, substring(content_json, 1, 300) AS content "
                                + "FROM runtime_item WHERE thread_id = ? ORDER BY seq", thread.threadId())
                        .forEach(i -> System.out.println("[g3dat-diag] " + i));
                throw e;
            }
            List<com.tioka.runtime.infrastructure.persistence.RuntimeItemDO> items =
                    cluster.a().getBean(ItemRepository.class).listByThread(thread.threadId());
            String toolResult = items.stream()
                    .filter(i -> i.getContentJson() != null && i.getContentJson().contains("rows"))
                    .map(i -> i.getContentJson())
                    .findFirst().orElse("");
            assertTrue(toolResult.contains("C1"), "工具结果应含华东行 C1: " + toolResult);
            assertFalse(toolResult.contains("C3"), "工具结果不应含华南行 C3");
            // C-17：attrs 经授予 scope 接线后，工具路径掩码口径与直调路径一致（华东 when 命中 → 原文；
            // 掩码差异本身由①直调断言承载）
            assertTrue(toolResult.contains("13812345678"),
                    "工具路径 phone 应与直调路径同掩码口径（华东原文）: " + toolResult);
            // output 为内嵌转义 JSON，按标签名匹配（详见 278 行口径说明）
            assertTrue(toolResult.contains("CONFIDENTIAL"), toolResult);
            // data.query 审计（DatasetGateway 管线末步）
            assertFalse(cluster.a().getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                            null, null, "data.query", "dataset", null, AuditOutcome.SUCCESS,
                            null, null, null, 50)).isEmpty(),
                    "审计应有 data.query SUCCESS");

            // —— 7. ③ 去向许可：IM 外部群拒发 CONFIDENTIAL 原文 ——
            var sinkRepo = cluster.a().getBean(
                    com.tioka.governance.application.label.LabelSinkRepository.class);
            principals.runAs(east, () -> {
                sinkRepo.save(new com.tioka.governance.application.label.LabelSinkRepository.LabelSinkRule(
                        ws, SinkKind.IM, "external-group", "INTERNAL", "DENY"));
                return null;
            });
            SinkGuard sinkGuard = cluster.a().getBean(SinkGuard.class);
            String confidentialPayload = "合同 HT-1 金额 900 万，联系电话 13812345678";
            principals.runAs(east, () -> {
                SinkCheckResult denied = sinkGuard.check(SinkKind.IM, "external-group",
                        com.tioka.framework.label.DataLabel.CONFIDENTIAL, confidentialPayload);
                assertEquals(SinkCheckResult.SinkVerdict.DENY, denied.verdict(),
                        "CONFIDENTIAL 原文发外部群应被拒");
                assertTrue(denied.reason().contains("超出去向"), denied.reason());
                // 对照：未配置收紧的 ref 走缺省（IM=CONFIDENTIAL）放行
                SinkCheckResult pass = sinkGuard.check(SinkKind.IM, "inner-group",
                        com.tioka.framework.label.DataLabel.CONFIDENTIAL, confidentialPayload);
                assertEquals(SinkCheckResult.SinkVerdict.PASS, pass.verdict());
                return null;
            });

            // —— 8. ③ 预演管线 egress-permit 步：DENY → 40332 + tool.invoke DENIED 审计 ——
            principals.runAs(east, () -> {
                sinkRepo.save(new com.tioka.governance.application.label.LabelSinkRepository.LabelSinkRule(
                        ws, SinkKind.BIZ, "data.query", "INTERNAL", "DENY"));
                return null;
            });
            BizException egressDenied = assertThrows(BizException.class, () ->
                    principals.runAs(east, () -> cluster.a().getBean(
                                    com.tioka.runtime.application.DefaultToolGateway.class).prepare(
                            ToolCallContexts.forTurn(east, thread.threadId(), handle.turnId(),
                                    seed.versionId(), "chat", List.of(), Map.of(), eastGrant.grantId()),
                            "data.query", Map.of("dataset", datasetName))));
            assertEquals(ErrorCode.DATA_LABEL_EXCEEDED, egressDenied.getErrorCode(),
                    "去向超限应 40332: " + egressDenied.getMessage());
            assertFalse(cluster.a().getBean(AuditApi.class).query(new AuditApi.AuditQuery(
                            ws, String.valueOf(eastUid), "tool.invoke", "tool", "data.query",
                            AuditOutcome.DENIED, null, null, null, 20)).isEmpty(),
                    "审计应有去向超限 tool.invoke DENIED");

            // —— 9. ④ 选模：标签 CONFIDENTIAL → MODEL_PLAN 通道 ——
            PlanLabelGate planGate = cluster.a().getBean(PlanLabelGate.class);
            ModelCallContext callCtx = new ModelCallContext(east, thread.threadId(),
                    handle.turnId(), seed.agentId(), seed.planId(), ModelPurpose.CHAT);
            assertEquals(com.tioka.framework.label.DataLabel.CONFIDENTIAL,
                    principals.runAs(east, () -> planGate.currentLabel(callCtx)),
                    "选模闸应读到真实 thread 标签");
            // 未获准（缺省 MODEL_PLAN=INTERNAL）→ NO_ELIGIBLE_MODEL(42210)
            BizException noModel = assertThrows(BizException.class,
                    () -> planGate.assertPlanAllowed(callCtx, seed.planId(),
                            com.tioka.framework.label.DataLabel.CONFIDENTIAL));
            assertEquals(ErrorCode.NO_ELIGIBLE_MODEL, noModel.getErrorCode());
            assertEquals(42210, ErrorCode.NO_ELIGIBLE_MODEL.getCode());
            // 播种获准计划行（max_label=CONFIDENTIAL）→ 同一计划放行
            principals.runAs(east, () -> {
                sinkRepo.save(new com.tioka.governance.application.label.LabelSinkRepository.LabelSinkRule(
                        ws, SinkKind.MODEL_PLAN, seed.planId(), "CONFIDENTIAL", "DENY"));
                return null;
            });
            principals.runAs(east, () -> {
                planGate.assertPlanAllowed(callCtx, seed.planId(),
                        com.tioka.framework.label.DataLabel.CONFIDENTIAL);
                return null;
            });
        }
    }
}

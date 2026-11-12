package com.tioka.server.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.application.DatasetService;
import com.tioka.eval.application.KnowledgeRecallEvalService;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KbToolProvider;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.knowledge.application.usage.KnowledgeAutoInjectSegment;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.runtime.api.context.ContextRenderContext;
import com.tioka.runtime.api.context.ContextZone;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.domain.ContextBuilder;
import com.tioka.runtime.kernel.domain.ContextSegmentRegistry;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 G3 闸门（C-22 转真）：规格见 M3-06 §3.4、ADR-056 §2/§3/§7。
 *
 * <p>关闭 mock、双实例、开启 Keycloak 鉴权。上传评测集 20 份文档 → 全部 READY →
 * 50 个问题跑 Recall@5 / MRR（不低于 CI 基线）→ 经真实 Turn 的 {@code kb.search}
 * 检索并回传引用号 → 无权限用户检索不到受限文档 → rerank 绑定与配额记账 →
 * KNW-14 模式与 CTX-01 的 D 区按 Turn 冻结。
 *
 * <p>环境口径：CI 无本地中文小模型，用 GateCluster 的确定性 dim=32 嵌入桩与
 * 应用层中文 bigram 分词承载同一纪律；偏离逐条登记在设计说明 M3-C22。
 */
@Tag("gate")
class G3KnowledgeRecallGateIT {

    private static final int TOP_K = 5;
    private static final String READER = "gate_reader";
    private static final String OTHER = "gate_other";

    @Test
    void hybridRecallRerankModesDocumentAclAndCitations() throws Exception {
        try (GateCluster cluster = GateCluster.start(2, "kb-search", Map.of(
                "tioka.eval.enabled", "true",
                "tioka.context.enabled", "true",
                "tioka.context.max-context-tokens", "12000",
                "tioka.knowledge.auto-inject.enabled", "true"
        ), true)) {
            assertEquals(2, cluster.size(), "闸门须双实例");
            ConfigurableApplicationContext a = cluster.a();
            ConfigurableApplicationContext b = cluster.b();
            PrincipalHolder principals = a.getBean(PrincipalHolder.class);
            JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
            ObjectMapper mapper = a.getBean(ObjectMapper.class);
            GateSeed.Bundle admin = GateSeed.seedPublishedAgent(a, cluster.llm().baseUrl());
            String ws = admin.workspaceId();

            Fixture f = principals.runAs(GateSeed.user(admin.userId(), ws), () -> {
                try {
                    return seed(a, ws, mapper, cluster.llm().baseUrl());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            // ---------- 1. 20 份文档全部 READY ----------
            assertEquals(20, f.docIds.size());
            Integer ready = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE kb_id = ? AND status = 'READY'",
                    Integer.class, f.mainKbId);
            assertEquals(20, ready, "评测集 20 份文档须全部 READY");

            // ---------- 2. 50 问 Recall@5 / MRR ----------
            String runId = principals.runAs(GateSeed.user(admin.userId(), ws),
                    () -> a.getBean(KnowledgeRecallEvalService.class)
                            .startRecallRun(f.datasetId, f.mainKbId, TOP_K));
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT kind, subject_type, status, summary_json FROM eval_run WHERE id = ?", runId);
            assertEquals("BATCH", runRow.get("kind"));
            assertEquals("KNOWLEDGE_SEARCH", runRow.get("subject_type"));
            assertEquals("COMPLETED", runRow.get("status"));
            Integer resultRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eval_result WHERE run_id = ?", Integer.class, runId);
            assertEquals(50 * 2, resultRows, "50 用例 × 2 评估器须各有 result 行");

            JsonNode summary = mapper.readTree(String.valueOf(runRow.get("summary_json")));
            double recall = summary.path("recallAt5").asDouble();
            double mrr = summary.path("mrr").asDouble();
            JsonNode baseline = readJson(mapper, "/knowledge/recall-gate-baseline.json");
            System.out.println("[C-22 实测] recallAt5=" + recall + " mrr=" + mrr
                    + " perCase=" + summary.path("perCase").size());
            assertEquals(50, summary.path("perCase").size(), "评测报告须逐用例留痕");
            assertTrue(recall >= baseline.path("recallAt5").asDouble() - 0.02,
                    "Recall@5 低于基线 2 个百分点以上，阻塞合并：实测=" + recall);
            assertTrue(recall >= 0.90, "Recall@5 绝对下限 0.90，防止基线被回填成恒真值：" + recall);
            assertTrue(mrr >= 0.5 && mrr <= 1.0 && summary.has("mrr"),
                    "MRR 须有记录且不低于 0.5：" + mrr);

            // ---------- 3. KNW-12：两路独立召回确实各自出数 ----------
            KnowledgeService knowledge = a.getBean(KnowledgeService.class);
            String literalQuery = "门禁卡 办理 补办";
            KnowledgeService.RetrievalOutcome hybrid = principals.runAs(
                    GateSeed.user(admin.userId(), ws),
                    () -> knowledge.retrieveDetailed(List.of(f.mainKbId), literalQuery, TOP_K, null));
            assertTrue(hybrid.vectorCount() > 0, "向量路须独立召回");
            assertTrue(hybrid.keywordCount() > 0, "全文路须独立召回，不得只在向量结果上打分");
            assertEquals(KnowledgeService.NoAnswerReason.NONE, hybrid.reason());
            assertTrue(hybrid.hits().stream().anyMatch(h -> f.docIds.get("security").equals(h.docId())),
                    "字面命中的门禁条款须进入 top5");

            // ---------- 4. 真实 Turn 经 kb.search 回传引用 ----------
            injectKbSearchScript(cluster.llm(), f.mainKbId, "差旅报销 流程 单据");
            var thread = GateSeed.createThread(a, admin);
            var handle = GateSeed.startTurn(a, admin, thread.threadId(),
                    "cm-g3-knowledge", "差旅报销流程怎么走");
            var done = GateSeed.awaitTerminal(a, admin, handle.turnId(), 90_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> String.valueOf(done.errorMessage()));
            List<RuntimeItemDO> toolResults = a.getBean(ItemRepository.class)
                    .listByThread(thread.threadId()).stream()
                    .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                    .toList();
            assertFalse(toolResults.isEmpty(), "Turn 应有 kb.search 的 tool_result");
            // output 是转义后的 JSON 字符串，按结构解析而非正则，避免转义引号误判
            String searchOutput = null;
            for (RuntimeItemDO item : toolResults) {
                if (item.getContentJson() == null || item.getContentJson().isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(item.getContentJson());
                if ("kb.search".equals(node.path("name").asText())
                        && node.path("ok").asBoolean()) {
                    searchOutput = node.path("output").asText();
                    break;
                }
            }
            assertNotNull(searchOutput, "kb.search 须返回 output");
            JsonNode hitNodes = mapper.readTree(searchOutput);
            assertTrue(hitNodes.isArray() && !hitNodes.isEmpty(),
                    "kb.search 须返回非空命中数组: " + searchOutput);
            String citationId = hitNodes.get(0).path("citationId").asText();
            assertTrue(citationId.startsWith("cit_"), "命中须带引用号: " + citationId);
            Integer citationRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_citation WHERE id = ?", Integer.class, citationId);
            assertEquals(1, citationRows, "引用号须可回查到 kb_citation 明细");

            // ---------- 5. 无权限用户检索不到受限文档 ----------
            String restrictedQuery = "受限条款 令牌 密级";
            List<String> readerHits = principals.runAs(principal(READER, ws),
                    () -> knowledge.retrieve(List.of(f.restrictedKbId), restrictedQuery, TOP_K))
                    .stream().map(KnowledgeService.RetrievalHit::docId).toList();
            List<String> otherHits = principals.runAs(principal(OTHER, ws),
                    () -> knowledge.retrieve(List.of(f.restrictedKbId), restrictedQuery, TOP_K))
                    .stream().map(KnowledgeService.RetrievalHit::docId).toList();
            assertTrue(readerHits.contains(f.restrictedDocId),
                    "ACL 授权用户应命中受限文档，否则权限测试退化为「什么都搜不到」");
            assertFalse(otherHits.contains(f.restrictedDocId),
                    "无权限用户不得检索到受限文档");
            assertFalse(otherHits.isEmpty(), "无权限用户仍应命中公开文档");
            assertTrue(otherHits.contains(f.publicDocId));

            // ---------- 6. KNW-04：绑定 rerank 生效、未绑定跳过且不设阈值 ----------
            String successUsageSql = "SELECT COUNT(*) FROM model_usage WHERE workspace_id = ? "
                    + "AND purpose = 'RERANK' AND status = 'SUCCESS'";
            Integer rerankUsageBefore = jdbc.queryForObject(successUsageSql, Integer.class, ws);
            principals.runAs(GateSeed.user(admin.userId(), ws), () -> {
                knowledge.configureRerank(f.mainKbId, f.rerankBindingId, 0.3d);
                return null;
            });
            KnowledgeService.RetrievalOutcome reranked = principals.runAs(
                    GateSeed.user(admin.userId(), ws),
                    () -> knowledge.retrieveDetailed(List.of(f.mainKbId), "差旅报销 流程", TOP_K, null));
            assertTrue(reranked.reranked(), "绑定后须经 ModelGateway.rerank");
            a.getBean(UsageRecorder.class).flush();
            Integer rerankUsageAfter = jdbc.queryForObject(successUsageSql, Integer.class, ws);
            assertTrue(rerankUsageAfter > rerankUsageBefore, "rerank 用量须计入空间配额台账");
            Integer usageForBinding = jdbc.queryForObject(successUsageSql + " AND binding_id = ?",
                    Integer.class, ws, f.rerankBindingId);
            assertTrue(usageForBinding > 0, "RERANK 用量须归到本次绑定的 binding");

            // 高阈值：阈值必须真的裁剪候选，并给出 BELOW_THRESHOLD 而不是笼统 NO_RESULT
            principals.runAs(GateSeed.user(admin.userId(), ws), () -> {
                knowledge.configureRerank(f.mainKbId, f.rerankBindingId, 0.99d);
                return null;
            });
            KnowledgeService.RetrievalOutcome highCut = principals.runAs(
                    GateSeed.user(admin.userId(), ws),
                    () -> knowledge.retrieveDetailed(List.of(f.mainKbId), "差旅报销 流程", TOP_K, null));
            assertTrue(highCut.rerankDropped() > 0, "高阈值须真实丢弃低分候选");
            assertTrue(highCut.hits().size() < TOP_K, "高阈值须削减命中集");
            if (highCut.hits().isEmpty()) {
                assertEquals(KnowledgeService.NoAnswerReason.BELOW_THRESHOLD, highCut.reason(),
                        "全部低于阈值时无答案原因须为 BELOW_THRESHOLD");
            }

            principals.runAs(GateSeed.user(admin.userId(), ws), () -> {
                knowledge.configureRerank(f.mainKbId, null, null);
                return null;
            });
            KnowledgeService.RetrievalOutcome unbound = principals.runAs(
                    GateSeed.user(admin.userId(), ws),
                    () -> knowledge.retrieveDetailed(List.of(f.mainKbId), "差旅报销 流程", TOP_K, null));
            assertFalse(unbound.reranked(), "未绑定时跳过 rerank");
            assertEquals(0, unbound.rerankDropped(), "未绑定时不得设阈值丢弃任何候选");

            // ---------- 7. KNW-14 模式 + CTX-01 D 区冻结 ----------
            KbToolProvider tools = a.getBean(KbToolProvider.class);
            assertTrue(tools.listTools(admin.agentVersionId(),
                    Map.of("knowledge", Map.of("mode", "AUTO"))).isEmpty(),
                    "AUTO 模式不得开放 kb.* 工具");
            assertTrue(tools.listTools(admin.agentVersionId(),
                            Map.of("knowledge", Map.of("mode", "TOOL"))).stream()
                    .anyMatch(t -> "kb.list".equals(t.name())), "TOOL 模式须开放 kb.list");
            ContextSegmentRegistry registry = a.getBean(ContextSegmentRegistry.class);
            assertTrue(registry.forZone(ContextZone.D_TURN_TAIL).stream()
                            .anyMatch(p -> "knowledge.auto_inject".equals(p.segmentName())),
                    "AUTO 注入须注册为 D 区段提供者");
            // 双实例共享 dispatcher：Turn 可能在 A 或 B 执行，冻结发生在执行实例上。
            ContextBuilder builderA = a.getBean(ContextBuilder.class);
            ContextBuilder builderB = b.getBean(ContextBuilder.class);
            assertTrue(builderA.segmentAssemblyEnabled() && builderB.segmentAssemblyEnabled(),
                    "两个实例都须开启四区组装");
            String hashA = builderA.frozenTailHash(handle.turnId());
            String hashB = builderB.frozenTailHash(handle.turnId());
            String textA = builderA.frozenTailText(handle.turnId());
            String textB = builderB.frozenTailText(handle.turnId());
            System.out.println("[C-22 冻结] turn=" + handle.turnId()
                    + " hashA=" + hashA + " hashB=" + hashB
                    + " lenA=" + textA.length() + " lenB=" + textB.length());
            assertFalse(hashA.isBlank() && hashB.isBlank(),
                    "Turn 执行实例的 D 区须按 Turn 冻结，前缀哈希可查");
            assertNotEquals(hashA.isBlank(), hashB.isBlank(),
                    "冻结只应发生在执行该 Turn 的单个实例上");
            ContextBuilder owner = hashA.isBlank() ? builderB : builderA;
            ContextBuilder idle = hashA.isBlank() ? builderA : builderB;
            String frozenHash = hashA.isBlank() ? hashB : hashA;
            String frozenText = hashA.isBlank() ? textB : textA;
            assertTrue(idle.frozenTailText(handle.turnId()).isEmpty(),
                    "未执行该 Turn 的实例不得有冻结段");

            // B1：注入必须是「真的检索结果」，空注入 / 无答案话术都不能蒙混过关
            assertTrue(frozenText.contains("[knowledge.auto_inject]"),
                    "D 区须为真实的知识自动注入段: " + frozenText);
            assertTrue(frozenText.contains("以下知识片段可用于回答"),
                    "D 区须含真实检索结果: " + frozenText);
            assertTrue(frozenText.contains("cit_"),
                    "D 区注入须带真实引用号，不能是空注入或「未找到」话术: " + frozenText);
            assertTrue(frozenText.contains("差旅"),
                    "D 区内容须与 Turn 的检索问题相关: " + frozenText);

            // 非恒真冻结验证：渲染计数是外部可证伪观测（不是再读一次同一个缓存 Map）；
            // 换 query 重新组装一次，若 D 区没有按 Turn 冻结，段提供者会被再次调用。
            ConfigurableApplicationContext ownerCtx = owner == builderA ? a : b;
            KnowledgeAutoInjectSegment injector = ownerCtx.getBean(KnowledgeAutoInjectSegment.class);
            long callsBefore = injector.renderCalls();
            assertTrue(callsBefore > 0, "执行实例确实渲染过知识自动注入段");
            List<RuntimeItemDO> turnItems = a.getBean(ItemRepository.class)
                    .listByThread(thread.threadId());
            List<Map<String, Object>> rebuilt = owner.build(turnItems,
                    ContextRenderContext.of(handle.turnId(), thread.threadId(), ws,
                            admin.agentVersionId(), "完全不同的一步问题"));
            assertEquals(callsBefore, injector.renderCalls(),
                    "同一 Turn 多步不得重新检索 / 渲染 D 区");
            assertFalse(rebuilt.isEmpty(), "重新组装须仍有上下文");
            String rebuiltTail = String.valueOf(rebuilt.get(rebuilt.size() - 1).get("content"));
            assertEquals(frozenText.strip(), rebuiltTail.strip(),
                    "同一 Turn 多步之间 D 区文本必须保持冻结，不得随新 query 重渲染");
            assertEquals(frozenHash, owner.frozenTailHash(handle.turnId()),
                    "同一 Turn 多步之间前缀哈希不变");

            // ---------- 8. 双实例共享数据可见 ----------
            List<String> bHits = b.getBean(PrincipalHolder.class).runAs(
                    GateSeed.user(admin.userId(), ws),
                    () -> b.getBean(KnowledgeService.class)
                            .retrieve(List.of(f.mainKbId), "差旅报销 流程", TOP_K))
                    .stream().map(KnowledgeService.RetrievalHit::docId).toList();
            assertFalse(bHits.isEmpty(), "实例 B 须能检索到实例 A 入库的文档");

            // ---------- 9. 开启鉴权 ----------
            assertNotNull(cluster.keycloak(), "闸门须开启 Keycloak 鉴权");
        }
    }

    // ------------------------------------------------------------------
    // 播种
    // ------------------------------------------------------------------

    private record Fixture(String mainKbId, String restrictedKbId, String restrictedDocId,
                           String publicDocId, String datasetId, String rerankBindingId,
                           Map<String, String> docIds) {}

    private static Fixture seed(ConfigurableApplicationContext ctx, String ws,
                                ObjectMapper mapper, String llmBaseUrl) throws Exception {
        KnowledgeService knowledge = ctx.getBean(KnowledgeService.class);
        ModelAdminService models = ctx.getBean(ModelAdminService.class);
        DatasetService datasets = ctx.getBean(DatasetService.class);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        String tag = Ids.newId("g3kb");
        var provider = models.createProvider(
                new CreateProviderRequest(tag + "-emb", llmBaseUrl, "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-m", "embed"));
        String embedding = models.createModelBinding(new CreateModelBindingRequest(
                lm, provider.id(), "text-embedding", "embedding", 32));

        // rerank 绑定走 ProviderClient 的确定性 mock（bigram 重合度打分），不依赖外部小模型
        var rerankProvider = models.createProvider(
                new CreateProviderRequest(tag + "-rr", "mock://openai", "k"));
        String rrLm = models.createLogicalModel(new CreateLogicalModelRequest(tag + "-rrm", "rerank"));
        String rerankBinding = models.createModelBinding(new CreateModelBindingRequest(
                rrLm, rerankProvider.id(), "rerank-stub", "rerank", null));

        var main = knowledge.createBase(ws, "g3-recall-main", embedding);
        var restricted = knowledge.createBase(ws, "g3-recall-restricted", embedding);

        JsonNode corpus = readJson(mapper, "/knowledge/eval-corpus.json");
        Map<String, String> docIds = new LinkedHashMap<>();
        for (JsonNode doc : corpus.path("docs")) {
            var ingested = knowledge.ingest(main.id(), doc.path("title").asText(),
                    doc.path("markdown").asText(), "upload", "md");
            if (!"READY".equals(ingested.status())) {
                throw new AssertionError("文档未 READY: " + doc.path("key").asText()
                        + " status=" + ingested.status());
            }
            docIds.put(doc.path("key").asText(), ingested.id());
        }

        // 受限库：一篇带文档级 ACL，一篇公开，用于「检索不到受限文档」的非退化断言
        var secret = knowledge.ingest(restricted.id(), "受限条款与密级令牌",
                "# 受限条款与密级令牌\n\n## 受限条款\n\n受限条款 令牌 密级 仅供授权读者查阅，"
                        + "其他主体不得检索本段内容。\n", "upload", "md");
        var pub = knowledge.ingest(restricted.id(), "公开条款与通用令牌",
                "# 公开条款与通用令牌\n\n## 公开条款\n\n受限条款 令牌 密级 是本节的主题词，"
                        + "但本段对所有空间成员公开可检索。\n", "upload", "md");
        jdbc.update("UPDATE kb_document SET acl_principals = ? WHERE id = ?",
                "[\"user:" + READER + "\"]", secret.id());

        var dataset = datasets.createDataset(new EvalApi.CreateDatasetCmd("g3-recall-20x50", null));
        List<EvalApi.EvalCase> cases = new ArrayList<>();
        for (JsonNode q : corpus.path("queries")) {
            String docKey = q.path("docKey").asText();
            String expected = docIds.get(docKey);
            assertNotNull(expected, "评测问题引用了不存在的文档: " + docKey);
            cases.add(new EvalApi.EvalCase(
                    mapper.readTree("{\"query\":\"" + q.path("text").asText() + "\"}"),
                    mapper.readTree("{\"references\":[\"" + expected + "\"]}"),
                    "IMPORT"));
        }
        assertEquals(50, cases.size(), "评测集须为 50 个问题");
        datasets.importCases(dataset.datasetId(), cases);

        return new Fixture(main.id(), restricted.id(), secret.id(), pub.id(),
                dataset.datasetId(), rerankBinding, Map.copyOf(docIds));
    }

    private static void injectKbSearchScript(LlmStubServer llm, String kbId, String query) {
        LlmStubServer.Script script = new LlmStubServer.Script();
        LlmStubServer.Call call1 = new LlmStubServer.Call();
        LlmStubServer.ToolCallSpec tc = new LlmStubServer.ToolCallSpec();
        tc.name = "kb.search";
        tc.arguments = Map.of("kbIds", List.of(kbId), "query", query, "topK", TOP_K);
        call1.tool_calls = List.of(tc);
        call1.usage = usage(10, 6);
        LlmStubServer.Call call2 = new LlmStubServer.Call();
        call2.content = "answer from kb";
        call2.stream_chunk_chars = 5;
        call2.usage = usage(14, 16);
        script.calls = new ArrayList<>(List.of(call1, call2));
        llm.putScript("kb-search", script);
    }

    private static LlmStubServer.UsageSpec usage(int prompt, int completion) {
        LlmStubServer.UsageSpec u = new LlmStubServer.UsageSpec();
        u.prompt = prompt;
        u.completion = completion;
        return u;
    }

    private static Principal principal(String userId, String ws) {
        return new Principal(userId, ws, PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "test");
    }

    private static JsonNode readJson(ObjectMapper mapper, String resource) throws Exception {
        try (InputStream in = G3KnowledgeRecallGateIT.class.getResourceAsStream(resource)) {
            assertNotNull(in, "资源缺失: " + resource);
            return mapper.readTree(in);
        }
    }
}

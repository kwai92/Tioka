package com.tioka.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.application.DatasetService;
import com.tioka.eval.application.KnowledgeRecallEvalService;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-16（KNW-07）：评测集与召回率基线进 CI。设计出处
 * docs/architecture/design-notes/03-推进规划与详细设计-A至E.md L518
 * 「评测进 CI：评测集由问题 + 期望命中的文档或分块组成，指标取 Recall@5、MRR；
 * CI 中用小型本地中文 embedding 模型保证结果可复现（本 IT 以 mock hashEmbed dim=32
 * 确定性替身承载同一纪律）；Recall@5 比基线低 2 个百分点以上则阻塞合并」。
 *
 * <p>同时承载 KNW-03 验收「召回率高于 200 字基线」：同一语料分别入
 * A（缺省结构化策略 + 上下文增强）与 B（childTargetChars=200 固定子块、无重叠、
 * context_enhancement=false）两个知识库，A 的 Recall@5 必须严格高于 B。
 *
 * <p>语料设计：6 篇主题彼此差异明显的 md 文档（# 标题 + 两个 ## 小节）；
 * 查询词面（空格分词）与目标文档标题路径显著重叠、与其他文档无关——标题路径
 * 只进 A 的嵌入输入与全文索引（context_enhancement），B 的分块文本不含标题，
 * 这是结构化切块收益的直接来源。哈希嵌入为字节/词面直方图型，区分度靠词面。
 *
 * <p>登记偏离候选：A/B 各建一个数据集（同题 12 用例、引用各自 base 的运行时
 * docId）——docId 为运行时生成、跨 base 无法共享单一数据集引用列；
 * 语义上仍是同一评测集（同问题 + 同期望文档）对双被测对象各跑一轮。
 */
@SpringBootTest
@Tag("it")
class KnowledgeRecallBaselineIT {

    private static final int TOP_K = 5;
    private static final String EVALUATOR_RECALL = "evl_builtin_recall_at_5";
    private static final String EVALUATOR_MRR = "evl_builtin_mrr";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        // C-16：召回评测执行器随主开关装配；批次子开关保持关闭（不触 BatchEvalService / RuntimeApi）
        registry.add("tioka.eval.enabled", () -> "true");
    }

    @Autowired KnowledgeService knowledge;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired DatasetService datasets;
    @Autowired KnowledgeRecallEvalService recallEval;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recallBaselineRegressionAndStructuredBeatsFixedChunking() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            try {
                runBaselineFlow(ws);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    private void runBaselineFlow(String ws) throws Exception {
        String bindingId = seedEmbeddingBinding(32);

        // ---- 语料：6 篇文档分别入 A（缺省结构化策略）与 B（200 字基线策略） ----
        var kbA = knowledge.createBase(ws, "recall-structured", bindingId);
        var kbB = knowledge.createBase(ws, "recall-baseline200", bindingId);
        // B：固定 200 字子块、无重叠、无上下文增强（200 字基线近似；ChunkPolicy 近似而非定长滑窗）
        knowledge.updateChunkPolicy(kbB.id(),
                "{\"childTargetChars\":200,\"childOverlapChars\":0,\"maxChildChars\":200}", false);

        Map<String, String> docIdsA = new LinkedHashMap<>();
        Map<String, String> docIdsB = new LinkedHashMap<>();
        for (Doc doc : corpus()) {
            var a = knowledge.ingest(kbA.id(), doc.title, doc.markdown, "upload", "md");
            var b = knowledge.ingest(kbB.id(), doc.title, doc.markdown, "upload", "md");
            assertEquals("READY", a.status(), "A 入库应 READY: " + doc.title);
            assertEquals("READY", b.status(), "B 入库应 READY: " + doc.title);
            docIdsA.put(doc.key, a.id());
            docIdsB.put(doc.key, b.id());
        }

        // ---- 评测集：12 用例（同题双集，引用各自 base 的 docId，动态填充） ----
        String datasetA = importDataset("knw07-recall-a", docIdsA);
        String datasetB = importDataset("knw07-recall-b", docIdsB);

        // ---- 双 run（同步执行器，startRecallRun 返回即终态） ----
        String runA = recallEval.startRecallRun(datasetA, kbA.id(), TOP_K);
        String runB = recallEval.startRecallRun(datasetB, kbB.id(), TOP_K);

        // ---- 断言 1：两次 run 落表正确 ----
        for (String runId : List.of(runA, runB)) {
            Map<String, Object> run = jdbc.queryForMap(
                    "SELECT kind, subject_type, subject_ref, status, dataset_id FROM eval_run WHERE id = ?", runId);
            assertEquals("BATCH", run.get("kind"));
            assertEquals("KNOWLEDGE_SEARCH", run.get("subject_type"));
            assertEquals("COMPLETED", run.get("status"));

            Integer resultRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eval_result WHERE run_id = ?", Integer.class, runId);
            assertEquals(12 * 2, resultRows, "每用例 ×2 评估器须有 result 行");
            Set<String> evaluatorIds = Set.copyOf(jdbc.queryForList(
                    "SELECT DISTINCT evaluator_id FROM eval_result WHERE run_id = ?", String.class, runId));
            assertEquals(Set.of(EVALUATOR_RECALL, EVALUATOR_MRR), evaluatorIds,
                    "evaluator_id 须为两个 V223 种子 ID");
        }

        JsonNode summaryA = summaryOf(runA);
        JsonNode summaryB = summaryOf(runB);
        double recallA = summaryA.path("recallAt5").asDouble();
        double recallB = summaryB.path("recallAt5").asDouble();
        double mrrA = summaryA.path("mrr").asDouble();
        double mrrB = summaryB.path("mrr").asDouble();
        System.out.println("[KNW-07 实测] A(结构化) recallAt5=" + recallA + " mrr=" + mrrA
                + " | B(200字基线) recallAt5=" + recallB + " mrr=" + mrrB);

        // ---- 断言 2：基线回归阻塞（低于基线 0.02 以上即失败） ----
        JsonNode baseline = readBaseline();
        assertTrue(recallA >= baseline.path("recallAt5").asDouble() - 0.02,
                "Recall@5 低于基线 2 个百分点以上，阻塞合并：A=" + recallA
                        + " 基线=" + baseline.path("recallAt5").asDouble());

        // ---- 断言 3（KNW-03 验收）：结构化召回严格高于 200 字基线 ----
        assertTrue(recallA > recallB,
                "KNW-03：A(结构化) recallAt5=" + recallA + " 必须严格高于 B(200字基线)=" + recallB);

        // ---- 断言 4：MRR 双 run 均存在且 ∈ [0,1]（只验证记录，不做阻塞断言） ----
        assertTrue(mrrA >= 0.0 && mrrA <= 1.0, "A MRR 越界: " + mrrA);
        assertTrue(mrrB >= 0.0 && mrrB <= 1.0, "B MRR 越界: " + mrrB);
        assertTrue(summaryA.has("perCase") && summaryA.path("perCase").size() == 12);
    }

    // ------------------------------------------------------------------
    // 评测集
    // ------------------------------------------------------------------

    /** 12 用例：每篇文档 2 条查询；词面锚定目标文档标题/正文，与其他文档无关。 */
    private static List<Query> queries() {
        return List.of(
                new Query("travel", "差旅报销 流程 单据"),
                new Query("travel", "差旅报销 住宿 标准"),
                new Query("warehouse", "库存盘点 周期 报送"),
                new Query("warehouse", "货架 分区 编码 规则"),
                new Query("contract", "合同签署 权限 会签"),
                new Query("contract", "续约 归档 提醒"),
                new Query("training", "员工培训 入职 考核"),
                new Query("training", "讲师 津贴 课时"),
                new Query("backup", "数据备份 频率 保留"),
                new Query("backup", "灾备 演练 接管"),
                new Query("security", "门禁卡 办理 补办"),
                new Query("security", "访客 登记 接待")
        );
    }

    private String importDataset(String name, Map<String, String> docIds) throws Exception {
        var dataset = datasets.createDataset(new EvalApi.CreateDatasetCmd(name, null));
        List<EvalApi.EvalCase> cases = new ArrayList<>();
        for (Query q : queries()) {
            cases.add(new EvalApi.EvalCase(
                    objectMapper.readTree("{\"query\":\"" + q.text + "\"}"),
                    objectMapper.readTree("{\"references\":[\"" + docIds.get(q.docKey) + "\"]}"),
                    "IMPORT"));
        }
        datasets.importCases(dataset.datasetId(), cases);
        return dataset.datasetId();
    }

    private JsonNode summaryOf(String runId) throws Exception {
        String summaryJson = jdbc.queryForObject(
                "SELECT summary_json FROM eval_run WHERE id = ?", String.class, runId);
        assertNotNull(summaryJson);
        return objectMapper.readTree(summaryJson);
    }

    private JsonNode readBaseline() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/knowledge/recall-baseline.json")) {
            assertNotNull(in, "基线文件缺失：test/resources/knowledge/recall-baseline.json");
            return objectMapper.readTree(in);
        }
    }

    // ------------------------------------------------------------------
    // 语料：主题彼此差异明显；标题承载主题词，正文用同义表述（标题路径只进 A）
    // ------------------------------------------------------------------

    private record Doc(String key, String title, String markdown) {}

    private record Query(String docKey, String text) {}

    private static List<Doc> corpus() {
        return List.of(
                new Doc("travel", "差旅费用指引", """
                        # 差旅费用指引

                        ## 差旅报销流程

                        员工出差归来后，需在五个工作日内把票据、行程单与审批单整理齐全，交给财务专员核验入账。垫付金额超过五千元的，可以在出发前向部门经理申请预支，回国后凭凭证多退少补。

                        跨市长途优先选择高铁二等座，夜间超过八小时的车程可以购买软卧。票据遗失的应当提供同行人证明与情况说明，经财务负责人签字确认后方可入账。

                        ## 住宿与交通标准

                        一线城市住宿上限为每晚六百元，二线城市四百元，县域地区三百元。市内出行凭票据实报实销，乘坐网约车需在备注栏注明事由与起止地点。

                        同一城市连续停留超过十个工作日的，住宿标准下浮百分之二十；节假日往返产生的附加费用不予受理。
                        """),
                new Doc("warehouse", "仓储管理规定", """
                        # 仓储管理规定

                        ## 库存盘点周期

                        每月最后一周进行全仓盘点，盘盈盘亏需要当日报送仓储主管，并附上系统截图作为凭证。季度末会同财务进行抽盘，抽盘比例不低于两成。

                        盘点期间暂停出入库作业，紧急放行需仓储主管与值班经理双签。盘点差异查明原因前，相关货位冻结拣选。

                        ## 货架分区编码

                        重型货物放置于底层货架，轻抛货置于高层；每个货位喷涂三位数分区编码，便于新员工快速上架。编码规则为「区-排-层」，例如 A-03-2 表示 A 区第三排第二层。

                        危险品单独存放于隔离区，与普通货物之间保持五米以上间距，并悬挂醒目标识。
                        """),
                new Doc("contract", "客户合同管理", """
                        # 客户合同管理

                        ## 合同签署权限

                        金额超过一百万的合同必须由法务会签，区域总监只能审批五十万以内的框架协议。涉及排他条款或者数据出境的，一律升级至总部评审。

                        电子签章与实体印章具有同等效力，用印申请需注明合同编号与对方主体全称，禁止在空白文本上用印。

                        ## 续约与归档

                        合同到期前三十天系统自动提醒续约；纸质文本扫描后归档到共享盘，原件交行政保管。退租与解约类文本保存期限为十年。

                        客户主体更名的，需补充签署主体变更确认函，历史文本不得手工涂改。
                        """),
                new Doc("training", "员工培训制度", """
                        # 员工培训制度

                        ## 新人入职培训

                        入职一个月内完成公司文化、信息安全与产品知识三门必修课，考核八十分为合格线。未通过的给予一次补考机会，仍不通过的暂缓转正。

                        导师需在四周内完成四次一对一带教记录，带教记录随转正材料一并归档。

                        ## 讲师津贴

                        内部讲师每课时补贴三百元，课件需提前一周提交培训委员会审核。外聘讲师的费用按采购流程单独申请。

                        年度授课满四十课时的讲师，在年度评优中予以加分；课件被评选为精品的额外奖励五百元。
                        """),
                new Doc("backup", "数据备份策略", """
                        # 数据备份策略

                        ## 备份频率与保留期

                        数据库每日凌晨两点做全量快照，日志每小时增量一次；全量保留三十天，增量保留七天。快照异地同步至灾备机房，同步延迟不得超过十五分钟。

                        备份完成后自动校验校验和，校验失败的立即告警并重试，连续三次失败升级至值班工程师。

                        ## 灾备演练

                        每半年组织一次恢复演练，验证备用机房可在两小时内接管核心业务系统。演练报告需记录实际耗时与差距分析。

                        演练期间生产数据只读，切换窗口避开月末结算高峰，演练完成后统一回切并核对账务一致性。
                        """),
                new Doc("security", "办公门禁与访客", """
                        # 办公门禁与访客

                        ## 门禁卡办理

                        新员工凭入职通知到一楼行政台办理门禁卡，丢失补办需缴纳工本费二十元。离职当日行政回收门禁卡并注销权限。

                        门禁卡仅限本人使用，转借他人的没收卡片并通报所在部门负责人。

                        ## 访客登记规则

                        来访人员须提前由接待人在系统提交访客申请，到达后在大堂换取临时访客牌。访客进入办公区须全程陪同，未经批准不得进入机房与档案室。

                        大型活动访客超过二十人的，需提前三个工作日报备物业并安排专属通道。
                        """)
        );
    }

    // ------------------------------------------------------------------
    // 支撑
    // ------------------------------------------------------------------

    private String seedEmbeddingBinding(int dim) {
        var provider = models.createProvider(new CreateProviderRequest(Ids.newId("p"), "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "embed"));
        return models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "text-embedding", "embedding", dim));
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
    }
}

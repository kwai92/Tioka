package com.tioka.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.spi.Evaluator;
import com.tioka.eval.evaluator.MrrEvaluator;
import com.tioka.eval.evaluator.RecallAt5Evaluator;
import com.tioka.eval.evaluator.EvaluatorRegistry;
import com.tioka.eval.infrastructure.EvalRunRepository;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import com.tioka.eval.infrastructure.persistence.EvalDatasetDO;
import com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO;
import com.tioka.eval.infrastructure.persistence.EvalResultDO;
import com.tioka.eval.infrastructure.persistence.EvalRunDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-16（KNW-07）：KnowledgeRecallEvalService 离线装配测试。
 * 假 KnowledgeSearchApi + 子类化 DatasetService / EvalRunRepository（内存替身，
 * 同 FeedbackIT / EvaluatorSpiIT 离线风格，不起 Spring、不落 DB）；覆盖 happy path
 * 与空间校验失败路径；落表行为由 KnowledgeRecallBaselineIT 全栈覆盖。
 */
class KnowledgeRecallEvalServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // 内存替身
    // ------------------------------------------------------------------

    /** 记录插入的 run / result 与终态；findRun/listResults 走内存。 */
    private static final class FakeRunRepository extends EvalRunRepository {
        EvalRunDO run;
        final List<EvalResultDO> results = new ArrayList<>();
        String finishedStatus;
        String finishedSummaryJson;

        FakeRunRepository() {
            super(null, null);
        }

        @Override
        public void insertRun(EvalRunDO row) {
            this.run = row;
        }

        @Override
        public EvalRunDO findRun(String runId) {
            return run;
        }

        @Override
        public void finishRun(String runId, String status, String summaryJson) {
            this.finishedStatus = status;
            this.finishedSummaryJson = summaryJson;
            run.setStatus(status);
            run.setSummaryJson(summaryJson);
        }

        @Override
        public void insertResult(EvalResultDO row) {
            results.add(row);
        }

        @Override
        public List<EvalResultDO> listResults(String runId) {
            return results;
        }
    }

    /** 内存数据集 / 用例 / 评估器行。 */
    private static final class FakeDatasetService extends DatasetService {
        EvalDatasetDO dataset;
        final List<EvalCaseDO> cases = new ArrayList<>();

        FakeDatasetService() {
            super(null, new ObjectMapper(), noPrincipalHolder());
        }

        /** 离线桩：无身份上下文（C-18 DatasetService 加密封集隔离依赖）。 */
        private static com.tioka.framework.security.PrincipalHolder noPrincipalHolder() {
            return new com.tioka.framework.security.PrincipalHolder() {
                @Override
                public com.tioka.framework.security.Principal current() {
                    throw new com.tioka.framework.error.BizException(
                            com.tioka.framework.error.ErrorCode.UNAUTHORIZED, "no principal");
                }

                @Override
                public java.util.Optional<com.tioka.framework.security.Principal> find() {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> T runAs(com.tioka.framework.security.Principal principal,
                                   java.util.function.Supplier<T> body) {
                    return body.get();
                }
            };
        }

        @Override
        public EvalDatasetDO findDataset(String datasetId) {
            return dataset;
        }

        @Override
        List<EvalCaseDO> casesForRun(String datasetId, Integer datasetVersion) {
            return cases;
        }

        @Override
        Map<String, EvalEvaluatorDO> evaluatorsById(List<String> ids) {
            return ids.stream().collect(Collectors.toMap(Function.identity(), this::builtinRow));
        }

        private EvalEvaluatorDO builtinRow(String id) {
            EvalEvaluatorDO row = new EvalEvaluatorDO();
            row.setId(id);
            row.setWorkspaceId(null);
            row.setType(KnowledgeRecallEvalService.EVALUATOR_RECALL_AT_5.equals(id)
                    ? new RecallAt5Evaluator().type() : new MrrEvaluator().type());
            row.setName(id);
            row.setConfigJson("{}");
            row.setBuiltin(true);
            return row;
        }
    }

    /** 确定性检索替身：query 含 "hit-a" 返回 doc_a 首位，含 "hit-b" 返回 doc_b 第二位，否则无目标。 */
    private static KnowledgeSearchApi fakeSearch() {
        return (kbId, query, topK) -> {
            List<KnowledgeSearchApi.Hit> hits = new ArrayList<>();
            if (query.contains("hit-a")) {
                hits.add(new KnowledgeSearchApi.Hit("doc_a", "chk_a1", "目标", 0.8, "目标A", "cit1"));
            } else if (query.contains("hit-b")) {
                hits.add(new KnowledgeSearchApi.Hit("doc_noise", "chk_n1", "噪声", 0.9, "噪声", "cit2"));
                hits.add(new KnowledgeSearchApi.Hit("doc_b", "chk_b1", "目标", 0.7, "目标B", "cit3"));
            } else {
                hits.add(new KnowledgeSearchApi.Hit("doc_noise", "chk_n1", "噪声", 0.9, "噪声", "cit4"));
                hits.add(new KnowledgeSearchApi.Hit("doc_x", "chk_x1", "无关", 0.8, "无关", "cit5"));
                hits.add(new KnowledgeSearchApi.Hit("doc_y", "chk_y1", "无关", 0.7, "无关", "cit6"));
            }
            return hits.stream().limit(Math.max(1, topK)).toList();
        };
    }

    private static PrincipalHolder holder() {
        return new PrincipalHolder() {
            @Override
            public Principal current() {
                return new Principal("u1", "ws1", PrincipalType.USER, Set.of(), Set.of(), "test");
            }

            @Override
            public java.util.Optional<Principal> find() {
                return java.util.Optional.of(current());
            }

            @Override
            public <T> T runAs(Principal principal, Supplier<T> body) {
                return body.get();
            }
        };
    }

    private static PlatformTransactionManager noopTxManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }

    private KnowledgeRecallEvalService service(FakeDatasetService datasets, FakeRunRepository runs,
                                               KnowledgeSearchApi search) {
        EvaluatorRegistry registry = new EvaluatorRegistry(List.<Evaluator>of(
                new RecallAt5Evaluator(), new MrrEvaluator()));
        return new KnowledgeRecallEvalService(datasets, runs, registry,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public KnowledgeSearchApi getObject(Object... args) {
                        return search;
                    }

                    @Override
                    public KnowledgeSearchApi getObject() {
                        return search;
                    }

                    @Override
                    public KnowledgeSearchApi getIfAvailable() {
                        return search;
                    }

                    @Override
                    public KnowledgeSearchApi getIfUnique() {
                        return search;
                    }

                    @Override
                    public java.util.stream.Stream<KnowledgeSearchApi> stream() {
                        return java.util.stream.Stream.empty();
                    }
                },
                holder(), mapper, noopTxManager());
    }

    private FakeDatasetService seededDataset(String workspaceId) {
        FakeDatasetService datasets = new FakeDatasetService();
        EvalDatasetDO ds = new EvalDatasetDO();
        ds.setId("ds1");
        ds.setWorkspaceId(workspaceId);
        ds.setName("recall");
        ds.setLatestVersion(1);
        datasets.dataset = ds;
        datasets.cases.add(caseOf("case_1", "{\"query\":\"hit-a 问题\"}", "{\"references\":[\"doc_a\"]}"));
        datasets.cases.add(caseOf("case_2", "{\"query\":\"hit-b 问题\"}", "{\"references\":[\"doc_b\"]}"));
        datasets.cases.add(caseOf("case_3", "{\"query\":\"无关 问题\"}", "{\"references\":[\"doc_c\"]}"));
        return datasets;
    }

    private static EvalCaseDO caseOf(String id, String input, String expected) {
        EvalCaseDO row = new EvalCaseDO();
        row.setId(id);
        row.setDatasetId("ds1");
        row.setVersion(1);
        row.setInputJson(input);
        row.setExpectedJson(expected);
        row.setSource("IMPORT");
        return row;
    }

    // ------------------------------------------------------------------
    // happy path
    // ------------------------------------------------------------------

    @Test
    void recallRunCompletesAndWritesResultsAndSummary() throws Exception {
        FakeDatasetService datasets = seededDataset("ws1");
        FakeRunRepository runs = new FakeRunRepository();
        KnowledgeRecallEvalService service = service(datasets, runs, fakeSearch());

        String runId = service.startRecallRun("ds1", "kb1", 5);
        assertNotNull(runId);
        assertEquals("ds1", runs.run.getDatasetId());
        assertEquals("kb1", runs.run.getSubjectRef());
        assertEquals(KnowledgeRecallEvalService.KIND_BATCH, runs.run.getKind());
        assertEquals(KnowledgeRecallEvalService.SUBJECT_KNOWLEDGE_SEARCH, runs.run.getSubjectType());
        assertEquals("ws1", runs.run.getWorkspaceId());
        assertEquals("COMPLETED", runs.finishedStatus);

        // 每用例 × 2 评估器 = 6 行，evaluator_id 为两个种子 ID
        assertEquals(6, runs.results.size());
        Set<String> evaluatorIds = runs.results.stream()
                .map(EvalResultDO::getEvaluatorId).collect(Collectors.toCollection(HashSet::new));
        assertEquals(Set.of(KnowledgeRecallEvalService.EVALUATOR_RECALL_AT_5,
                KnowledgeRecallEvalService.EVALUATOR_MRR), evaluatorIds);

        // 用例级：case_1 → recall 1/mrr 1；case_2 → recall 1/mrr 0.5；case_3 → 全 0
        JsonNode summary = mapper.readTree(runs.finishedSummaryJson);
        assertEquals(3, summary.path("totalCases").asInt());
        assertEquals(2.0 / 3, summary.path("recallAt5").asDouble(), 1e-9);
        assertEquals(0.5, summary.path("mrr").asDouble(), 1e-9);
        assertEquals("kb1", summary.path("kbId").asText());
        assertEquals(5, summary.path("topK").asInt());
        JsonNode perCase = summary.path("perCase");
        assertEquals(1.0, perCase.get(0).path("recall").asDouble(), 1e-9);
        assertEquals(1, perCase.get(0).path("rankFirst").asInt());
        assertEquals(0.5, perCase.get(1).path("mrr").asDouble(), 1e-9);
        assertEquals(2, perCase.get(1).path("rankFirst").asInt());
        assertEquals(0, perCase.get(2).path("rankFirst").asInt());

        // getRun 视图
        KnowledgeRecallEvalService.EvalRunView view = service.getRun(runId);
        assertEquals("COMPLETED", view.status());
        assertEquals(6, view.results().size());
    }

    // ------------------------------------------------------------------
    // 校验失败路径
    // ------------------------------------------------------------------

    @Test
    void foreignWorkspaceDatasetIsNotFound() {
        FakeDatasetService datasets = seededDataset("ws-other");
        FakeRunRepository runs = new FakeRunRepository();
        KnowledgeRecallEvalService service = service(datasets, runs, fakeSearch());

        BizException ex = assertThrows(BizException.class,
                () -> service.startRecallRun("ds1", "kb1", 5));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        org.junit.jupiter.api.Assertions.assertNull(runs.run, "空间校验失败不得落 run");
    }

    @Test
    void missingSearchApiFailsFastWithNotImplemented() {
        FakeDatasetService datasets = seededDataset("ws1");
        FakeRunRepository runs = new FakeRunRepository();
        KnowledgeRecallEvalService service = service(datasets, runs, null);

        BizException ex = assertThrows(BizException.class,
                () -> service.startRecallRun("ds1", "kb1", 5));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("KnowledgeSearchApi"));
    }

    @Test
    void blankQueryFailsTheRun() {
        FakeDatasetService datasets = seededDataset("ws1");
        datasets.cases.add(caseOf("case_bad", "{\"text\":\"没有 query\"}", "{\"references\":[\"doc_a\"]}"));
        FakeRunRepository runs = new FakeRunRepository();
        KnowledgeRecallEvalService service = service(datasets, runs, fakeSearch());

        service.startRecallRun("ds1", "kb1", 5);
        assertEquals("FAILED", runs.finishedStatus);
        assertTrue(runs.finishedSummaryJson.contains("query"));
    }
}

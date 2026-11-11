package com.tioka.ontology.application.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.PathValueKind;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.rule.RuleEvaluator;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖统计单元测试（ONT-03，M3-03 §8）：四态各一用例（有 / 无 / 空白 / 不适用）
 * + 缺失期望项列表断言。REVIEW 归未知（冻结口径）。
 */
class CoverageServiceTest {

    private static final String DEF_KEY = "test.lease";
    private static final String INSTANCE_KEY = "lease:S001:HT-001";

    private DefinitionService definitions;
    private OntValueIndexRepository valueIndex;
    private CoverageService service;

    @BeforeEach
    void setUp() {
        definitions = new DefinitionService(new ObjectMapper(), null);
        valueIndex = mock(OntValueIndexRepository.class);
        definitions.saveDraft("test.pack", "1.0.0", definition());
        service = new CoverageService(fixedProvider(definitions), valueIndex, new ObjectMapper(),
                emptyProvider(), topic -> null);
    }

    /** 模型定义：主题 rent 期望对象类型 feeItem 与陈述类型 OBLIGATION。 */
    private static ModelDefinition definition() {
        return new ModelDefinition(
                1, DEF_KEY, "lease", null,
                List.of(new ModelDefinition.ObjectType("feeItem", "feeItems",
                        List.of(),
                        List.of(new ModelDefinition.AttrGroup("sched", List.of(
                                ModelDefinition.AttrDef.leaf("baseAmount", PathValueKind.AMOUNT,
                                        "CNY", null, List.of("rent"))))),
                        List.of(), List.of())),
                List.of(new ModelDefinition.StatementType("OBLIGATION", List.of(
                        new ModelDefinition.StatementParam("action", PathValueKind.TEXT,
                                null, List.of("rent"))))),
                List.of(new ModelDefinition.Topic("rent",
                        List.of("feeItem"), List.of("OBLIGATION"))),
                Map.of(), null, List.of(), List.of(), List.of());
    }

    private static OntInstanceVersionDO instance() {
        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(INSTANCE_KEY);
        row.setVersion(1);
        row.setStatus("CONFIRMED");
        row.setDefKey(DEF_KEY);
        row.setDefVersion(0);
        row.setModelJson("{}");
        return row;
    }

    private static OntValueIndexDO row(String path, String status, List<String> topics) {
        OntValueIndexDO r = new OntValueIndexDO();
        r.setInstanceKey(INSTANCE_KEY);
        r.setVersion(1);
        r.setPath(path);
        r.setStatus(status);
        r.setValueText("10000");
        r.setTopics(topics);
        return r;
    }

    private static final String AMOUNT_PATH = "m.feeItems[RENT@S1].sched.baseAmount";
    private static final String ACTION_PATH = "m.statements[OBLIGATION].action";

    @Test
    void 全部期望项已知_为有() {
        when(valueIndex.list(INSTANCE_KEY, 1)).thenReturn(List.of(
                row(AMOUNT_PATH, "KNOWN", List.of("rent")),
                row(ACTION_PATH, "KNOWN", List.of("rent"))));
        List<CoverageService.TopicCoverage> out = service.coverage(instance());
        assertEquals(1, out.size());
        assertEquals("rent", out.get(0).topicKey());
        assertEquals(CoverageService.State.PRESENT, out.get(0).state());
        assertTrue(out.get(0).missingItems().isEmpty());
    }

    @Test
    void 全部未知_为无且缺失项全列() {
        when(valueIndex.list(INSTANCE_KEY, 1)).thenReturn(List.of(
                row(AMOUNT_PATH, "UNKNOWN", List.of("rent")),
                row(ACTION_PATH, "REVIEW", List.of("rent"))));
        List<CoverageService.TopicCoverage> out = service.coverage(instance());
        assertEquals(CoverageService.State.MISSING, out.get(0).state());
        // REVIEW 归未知（冻结口径）：OBLIGATION 计为缺失
        assertEquals(List.of("feeItem", "OBLIGATION"), out.get(0).missingItems());
    }

    @Test
    void 存在空白_为空白并列缺失项() {
        when(valueIndex.list(INSTANCE_KEY, 1)).thenReturn(List.of(
                row(AMOUNT_PATH, "KNOWN", List.of("rent")),
                row(ACTION_PATH, "BLANK", List.of("rent"))));
        List<CoverageService.TopicCoverage> out = service.coverage(instance());
        assertEquals(CoverageService.State.BLANK, out.get(0).state());
        assertEquals(List.of("OBLIGATION"), out.get(0).missingItems());
    }

    @Test
    void 期望项不适用_为不适用() {
        when(valueIndex.list(INSTANCE_KEY, 1)).thenReturn(List.of(
                row(AMOUNT_PATH, "NA", List.of("rent")),
                row(ACTION_PATH, "KNOWN", List.of("rent"))));
        List<CoverageService.TopicCoverage> out = service.coverage(instance());
        assertEquals(CoverageService.State.NOT_APPLICABLE, out.get(0).state());
    }

    @Test
    void 部分未知_为空白且缺失项只列未知项() {
        // feeItem 已知、OBLIGATION 未知（非空白）：部分缺失 → 空白，缺失项只列 OBLIGATION
        when(valueIndex.list(INSTANCE_KEY, 1)).thenReturn(List.of(
                row(AMOUNT_PATH, "KNOWN", List.of("rent")),
                row(ACTION_PATH, "UNKNOWN", List.of("rent"))));
        List<CoverageService.TopicCoverage> out = service.coverage(instance());
        assertEquals(CoverageService.State.BLANK, out.get(0).state());
        assertEquals(List.of("OBLIGATION"), out.get(0).missingItems());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) Mockito.mock(ObjectProvider.class);
    }

    /** 定值 ObjectProvider（DefinitionService 弱依赖桩）。 */
    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}

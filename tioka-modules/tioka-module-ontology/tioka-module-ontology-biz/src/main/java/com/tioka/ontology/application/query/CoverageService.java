package com.tioka.ontology.application.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.api.rule.RuleResult;
import com.tioka.ontology.api.rule.RuleSpec;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.rule.RuleEvaluator;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 主题覆盖统计（ONT-03，M3-03 §8 {@code @model.coverage}）：
 * 输入 = ont_value_index（按 topics 统计）+ 模型定义中该主题期望的对象类型与陈述类型
 * + 领域包 CEL 适用条件。四态：
 * <ul>
 *   <li>{@link State#PRESENT} 有：全部期望项都有已知值；</li>
 *   <li>{@link State#MISSING} 无：全部未知；</li>
 *   <li>{@link State#BLANK} 空白：存在空白 / 部分缺失；</li>
 *   <li>{@link State#NOT_APPLICABLE} 不适用：主题适用条件（领域包 CEL）为 false，或
 *       期望项任一取值在值索引中为 NOT_APPLICABLE。</li>
 * </ul>
 * 状态映射（口径已冻结）：KNOWN(explicit/derived/default/inherited) → 已知；BLANK → 空白；
 * UNKNOWN 与 REVIEW → 未知（REVIEW 是折叠复核项，归未知）；NOT_APPLICABLE(NA) → 触发「不适用」。
 * 返回同时列出缺失的期望项，供审查 Agent 定向复查。
 *
 * <p>期望项匹配：对象类型按 {@code m.<collectionKey>} 路径前缀；陈述类型按
 * {@code [<typeKey>]} 身份选择器（确认模型陈述集合元素以 type 字段为身份）。
 */
@Service
public class CoverageService {

    /** 主题覆盖四态。 */
    public enum State { PRESENT, MISSING, BLANK, NOT_APPLICABLE }

    /** 单主题覆盖结果；missingItems 为缺失的期望项（typeKey）。 */
    public record TopicCoverage(String topicKey, State state, List<String> missingItems) {
        public TopicCoverage {
            missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
        }
    }

    private final DefinitionService definitions;
    private final OntValueIndexRepository valueIndex;
    private final ObjectMapper mapper;
    private final ObjectProvider<RuleEvaluator> rules;
    /** 主题 → 领域包 CEL 适用条件（无条件返回 null；M3-04 领域包复核时挂接真实来源，登记偏差）。 */
    private final Function<String, String> topicConditions;

    public CoverageService(DefinitionService definitions,
                           OntValueIndexRepository valueIndex,
                           ObjectMapper mapper,
                           ObjectProvider<RuleEvaluator> rules,
                           Function<String, String> topicConditions) {
        this.definitions = definitions;
        this.valueIndex = valueIndex;
        this.mapper = mapper;
        this.rules = rules;
        this.topicConditions = topicConditions;
    }

    public List<TopicCoverage> coverage(OntInstanceVersionDO instance) {
        PublishedDefinition def = resolveDefinition(instance);
        if (def == null || def.definition() == null) {
            return List.of();
        }
        List<OntValueIndexDO> rows = valueIndex.list(instance.getInstanceKey(), instance.getVersion());
        JsonNode model = readModel(instance.getModelJson());
        ModelDefinition definition = def.definition();

        List<TopicCoverage> out = new ArrayList<>();
        for (ModelDefinition.Topic topic : definition.topics()) {
            out.add(coverageOf(topic, definition, rows, model));
        }
        return out;
    }

    private TopicCoverage coverageOf(ModelDefinition.Topic topic, ModelDefinition definition,
                                     List<OntValueIndexDO> rows, JsonNode model) {
        // 1. 领域包 CEL 适用条件：false → 不适用
        String condition = topicConditions == null ? null : topicConditions.apply(topic.topicKey());
        if (condition != null && !condition.isBlank()) {
            RuleEvaluator evaluator = rules.getIfAvailable();
            if (evaluator != null) {
                RuleResult result = evaluator.evaluate(
                        new RuleSpec("coverage." + topic.topicKey(), condition,
                                List.of(), "true", null, List.of()),
                        model, java.util.Map.of(), null);
                if (result.conclusion() == RuleResult.Conclusion.NOT_APPLICABLE) {
                    return new TopicCoverage(topic.topicKey(), State.NOT_APPLICABLE, List.of());
                }
            }
        }

        // 2. 期望项 → 值索引行匹配
        List<ExpectedItem> items = expectedItems(topic, definition);
        boolean anyKnown = false;
        boolean anyBlank = false;
        boolean anyUnknown = false;
        boolean anyNa = false;
        Set<String> missing = new LinkedHashSet<>();
        for (ExpectedItem item : items) {
            List<OntValueIndexDO> matched = rowsOfItem(item, rows);
            boolean itemKnown = !matched.isEmpty();
            for (OntValueIndexDO row : matched) {
                switch (ValueClass.of(row.getStatus())) {
                    case KNOWN -> anyKnown = true;
                    case BLANK -> {
                        anyBlank = true;
                        itemKnown = false;
                    }
                    case UNKNOWN -> {
                        anyUnknown = true;
                        itemKnown = false;
                    }
                    case NOT_APPLICABLE -> anyNa = true;
                }
            }
            if (!itemKnown) {
                missing.add(item.typeKey());
            }
        }

        // 3. 四态聚合：不适用 > 无（全未知） > 空白（存在空白 / 部分缺失） > 有
        if (anyNa) {
            return new TopicCoverage(topic.topicKey(), State.NOT_APPLICABLE, List.of());
        }
        if (!anyKnown) {
            return new TopicCoverage(topic.topicKey(), State.MISSING, List.copyOf(missing));
        }
        if (anyBlank || anyUnknown) {
            return new TopicCoverage(topic.topicKey(), State.BLANK, List.copyOf(missing));
        }
        return new TopicCoverage(topic.topicKey(), State.PRESENT, List.of());
    }

    private List<ExpectedItem> expectedItems(ModelDefinition.Topic topic, ModelDefinition definition) {
        List<ExpectedItem> items = new ArrayList<>();
        for (String typeKey : topic.expectedObjectTypes()) {
            definition.objectTypes().stream()
                    .filter(ot -> typeKey.equals(ot.typeKey()))
                    .findFirst()
                    .ifPresentOrElse(
                            ot -> items.add(new ExpectedItem(ot.typeKey(), ot.collectionKey(), null)),
                            () -> items.add(new ExpectedItem(typeKey, typeKey, null)));
        }
        for (String typeKey : topic.expectedStatementTypes()) {
            items.add(new ExpectedItem(typeKey, null, typeKey));
        }
        return items;
    }

    private static List<OntValueIndexDO> rowsOfItem(ExpectedItem item, List<OntValueIndexDO> rows) {
        List<OntValueIndexDO> out = new ArrayList<>();
        for (OntValueIndexDO row : rows) {
            String path = row.getPath() == null ? "" : row.getPath();
            if (item.collectionKey() != null
                    && (path.startsWith("m." + item.collectionKey() + ".")
                        || path.startsWith("m." + item.collectionKey() + "["))) {
                out.add(row);
            } else if (item.statementSelector() != null
                    && path.contains("[" + item.statementSelector() + "]")) {
                out.add(row);
            }
        }
        return out;
    }

    private PublishedDefinition resolveDefinition(OntInstanceVersionDO instance) {
        return definitions.getVersion(instance.getDefKey(), instance.getDefVersion())
                .or(() -> definitions.currentPublishedVersion(instance.getDefKey()))
                .orElseGet(() -> definitions.getDraft(instance.getDefKey()).orElse(null));
    }

    private JsonNode readModel(String modelJson) {
        if (modelJson == null || modelJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(modelJson);
        } catch (Exception e) {
            throw new IllegalStateException("model_json 解析失败", e);
        }
    }

    /** 期望项：对象类型（按 collectionKey 前缀匹配）或陈述类型（按身份选择器匹配）。 */
    private record ExpectedItem(String typeKey, String collectionKey, String statementSelector) {
    }

    /** ont_value_index.status 存储口径 → 四态输入分类。 */
    private enum ValueClass {
        KNOWN, BLANK, UNKNOWN, NOT_APPLICABLE;

        static ValueClass of(String stored) {
            if (stored == null) {
                return UNKNOWN;
            }
            return switch (stored.trim().toUpperCase()) {
                // KNOWN 涵盖 explicit/derived/default/inherited（重建时已归一）；REVIEW 归未知（冻结口径）
                case "KNOWN" -> KNOWN;
                case "BLANK" -> BLANK;
                case "NA" -> NOT_APPLICABLE;
                default -> UNKNOWN;
            };
        }
    }
}

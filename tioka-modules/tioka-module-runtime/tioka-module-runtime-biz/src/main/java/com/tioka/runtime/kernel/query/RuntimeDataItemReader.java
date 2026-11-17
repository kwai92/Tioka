package com.tioka.runtime.kernel.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.DataItemOutcome;
import com.tioka.ontology.api.schema.DataItemReader;
import com.tioka.ontology.api.schema.DataItemView;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * data item 的读取入口实现（ADR-066 §1.3 / SCH-07）。
 *
 * <p>消费方（Flow 节点、业务工具、前端渲染器）只能经此读取结构化产出的 payload：
 * 只有 {@link DataItemOutcome#VALID} / {@link DataItemOutcome#VALID_WITH_UNKNOWN} 结局交付 payload，
 * 其余结局返回视图但 payload 为 {@code null}（失败时的候选存档按 ADR-066 §1.1 不可消费）。
 * 解析 data item `content_json` 只允许出现在本类，由 ArchUnit 规则守护。
 */
@Component
public class RuntimeDataItemReader implements DataItemReader {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDataItemReader.class);

    /** 可交付 payload 的结局（ADR-066 §1.1）。 */
    private static final Set<DataItemOutcome> DELIVERABLE =
            Set.of(DataItemOutcome.VALID, DataItemOutcome.VALID_WITH_UNKNOWN);

    private final ItemRepository items;
    private final ObjectMapper mapper;

    public RuntimeDataItemReader(ItemRepository items, ObjectMapper mapper) {
        this.items = items;
        this.mapper = mapper;
    }

    @Override
    public Optional<DataItemView> read(String itemId, String schemaRef) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        RuntimeItemDO item = items.findById(itemId.strip());
        if (item == null || !ItemType.DATA.wire().equals(item.getType())) {
            return Optional.empty();
        }
        JsonNode content = parse(item.getContentJson());
        if (content == null || !content.isObject()) {
            return Optional.empty();
        }
        String itemSchemaRef = text(content, "schemaRef");
        if (schemaRef != null && !schemaRef.isBlank() && !schemaRef.equals(itemSchemaRef)) {
            return Optional.empty();
        }
        DataItemOutcome outcome = outcome(content);
        JsonNode payload = DELIVERABLE.contains(outcome) ? content.get("payload") : null;
        if (payload != null && payload.isNull()) {
            payload = null;
        }
        return Optional.of(new DataItemView(item.getId(), itemSchemaRef, outcome,
                text(content, "reasonCode"), payload));
    }

    private JsonNode parse(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(contentJson);
        } catch (Exception e) {
            log.warn("data item 内容不可解析：{}", e.toString());
            return null;
        }
    }

    private static String text(JsonNode content, String field) {
        JsonNode node = content.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 结局字段缺失或非法时按「不可交付」处理（fail-closed）；SCH-07 之前的产出只有 valid 标记。 */
    private static DataItemOutcome outcome(JsonNode content) {
        String raw = text(content, "outcome");
        if (raw != null) {
            try {
                return DataItemOutcome.valueOf(raw);
            } catch (IllegalArgumentException e) {
                log.warn("data item 结局不可识别：{}", raw);
                return DataItemOutcome.FAILED;
            }
        }
        return content.path("valid").asBoolean(false) ? DataItemOutcome.VALID : DataItemOutcome.FAILED;
    }
}

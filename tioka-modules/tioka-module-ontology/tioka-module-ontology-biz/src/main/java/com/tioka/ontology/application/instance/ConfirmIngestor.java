package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.json.CanonicalJson;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.ontology.api.mq.OntologyMqTopics;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.domain.ModelPathDiff;
import com.tioka.ontology.domain.ValueIndexWalker;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PublishedDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 消费 {@code contract.model.confirmed}（ONT-02 确认投影，M3-03 §7 / ADR-029 第 50 条）：
 *
 * <pre>
 * 1. 幂等：ont_instance_version 已有 (instanceKey, version) → ACK（返回）
 * 2. 调业务连接器 getConfirmedModel(instanceKey, version) 拉取完整模型
 * 3. sha256(canonicalJson(model)) != contentHash → 拒收（抛 ONT_HASH_MISMATCH：
 *    重试耗尽后由 IdempotentDeliveryService 写死信，不 ACK），告警，不做合并
 * 4. 用对应 def_version 的 Schema 校验；失败同样拒收
 * 5. 一个事务（投递适配器包裹）：写新版本 CONFIRMED，旧版本 SUPERSEDED；
 *    diff_prev、diff_draft；重建 ont_value_index；outbox 发 tioka.ontology.instance_confirmed.v1
 * </pre>
 */
public class ConfirmIngestor extends IdempotentConsumer<Map<String, Object>> {

    static final String ERROR_HASH_MISMATCH = "ONT_HASH_MISMATCH";
    static final String ERROR_SCHEMA_INVALID = "ONT_SCHEMA_INVALID";
    static final String ERROR_MODEL_FETCH = "ONT_MODEL_FETCH_FAILED";

    private static final Logger log = LoggerFactory.getLogger(ConfirmIngestor.class);

    private final ObjectMapper mapper;
    private final OntInstanceVersionRepository versions;
    private final OntDraftRepository drafts;
    private final OntValueIndexRepository valueIndex;
    private final ObjectProvider<ConfirmedModelClient> modelClient;
    private final ObjectProvider<SchemaRegistry> schemaRegistry;
    private final ObjectProvider<DefinitionService> definitions;
    private final EventPublisher publisher;

    public ConfirmIngestor(ObjectMapper mapper,
                           OntInstanceVersionRepository versions,
                           OntDraftRepository drafts,
                           OntValueIndexRepository valueIndex,
                           ObjectProvider<ConfirmedModelClient> modelClient,
                           ObjectProvider<SchemaRegistry> schemaRegistry,
                           ObjectProvider<DefinitionService> definitions,
                           EventPublisher publisher) {
        this.mapper = mapper;
        this.versions = versions;
        this.drafts = drafts;
        this.valueIndex = valueIndex;
        this.modelClient = modelClient;
        this.schemaRegistry = schemaRegistry;
        this.definitions = definitions;
        this.publisher = publisher;
    }

    @Override
    protected String consumerName() {
        return "ontology-confirm";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return OntologyMqTopics.BIZ_LEASE_TOPIC;
    }

    @Override
    protected String tagExpression() {
        return OntologyMqTopics.BIZ_EVENT_MODEL_CONFIRMED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        ContractModelConfirmed confirmed = parse(payload);
        String instanceKey = confirmed.contractKey();
        int version = confirmed.version();

        // 1. 幂等（业务级）：已有 (instanceKey, version) → ACK
        if (versions.find(instanceKey, version).isPresent()) {
            log.info("contract.model.confirmed 重复投递，跳过 instanceKey={} version={}", instanceKey, version);
            return;
        }

        // 2. 拉取完整模型
        ConfirmedModelClient client = modelClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(ERROR_MODEL_FETCH + ": ConfirmedModelClient 未装配（CON-07a 未接线）");
        }
        JsonNode model;
        try {
            model = client.fetch(instanceKey, version);
        } catch (RuntimeException e) {
            throw new IllegalStateException(ERROR_MODEL_FETCH + ": " + e.getMessage(), e);
        }
        if (model == null || model.isNull()) {
            throw new IllegalStateException(ERROR_MODEL_FETCH + ": 业务系统返回空模型");
        }

        // 3. 内容哈希（RFC 8785 + SHA-256，业务系统与 Tioka 同一算法）→ 不一致拒收进死信
        String actualHash = CanonicalJson.sha256Hex(mapper, model);
        if (!actualHash.equalsIgnoreCase(confirmed.contentHash())) {
            log.warn("{}: instanceKey={} version={} expected={} actual={}",
                    ERROR_HASH_MISMATCH, instanceKey, version, confirmed.contentHash(), actualHash);
            throw new IllegalStateException(ERROR_HASH_MISMATCH
                    + ": contentHash 不一致 expected=" + confirmed.contentHash() + " actual=" + actualHash);
        }

        // 4. Schema 校验（对应 def_version 的对象类型 Schema；失败拒收）
        Optional<OntDraftDO> draft = drafts.find(instanceKey);
        String defKey = draft.map(OntDraftDO::getDefKey).orElse(instanceKey.split(":")[0] + ".contract");
        int defVersion = draft.map(OntDraftDO::getDefVersion).orElse(0);
        validateAgainstSchemas(defKey, defVersion, model);

        // 5. 单事务写：新版本 CONFIRMED + 旧版本 SUPERSEDED + diff_prev/diff_draft + 值索引重建 + outbox
        confirm(instanceKey, version, confirmed, defKey, defVersion, draft, model);
    }

    /** 步骤 5（由投递适配器事务包裹；离线测试内为原子内存操作）。 */
    void confirm(String instanceKey, int version, ContractModelConfirmed confirmed,
                 String defKey, int defVersion, Optional<OntDraftDO> draft, JsonNode model) {
        // diff_prev：与上一确认版的路径级差异
        Optional<OntInstanceVersionDO> prev = versions.findLatestConfirmed(instanceKey);
        String diffPrev = null;
        if (prev.isPresent()) {
            JsonNode prevModel = readTree(prev.orElseThrow().getModelJson());
            diffPrev = writeDiff(ModelPathDiff.diff(prevModel, model));
        }
        // diff_draft：与对应草稿的差异（= 人工更正）
        String diffDraft = null;
        JsonNode draftModel = null;
        if (draft.isPresent() && draft.orElseThrow().getModelJson() != null) {
            draftModel = readTree(draft.orElseThrow().getModelJson());
            if (draftModel != null) {
                diffDraft = writeDiff(ModelPathDiff.diff(draftModel, model));
            }
        }

        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(instanceKey);
        row.setVersion(version);
        row.setStatus("CONFIRMED");
        row.setDefKey(defKey);
        row.setDefVersion(defVersion);
        row.setStoreKey(draft.map(OntDraftDO::getStoreKey).orElse(storeKeyOf(instanceKey)));
        row.setModelJson(model.toString());
        row.setContentHash(confirmed.contentHash());
        row.setDiffPrev(diffPrev);
        row.setDiffDraft(diffDraft);
        row.setRefsJson(refsOf(model));
        row.setConfirmedBy(confirmed.confirmedBy());
        row.setConfirmedAt(Instant.now());
        row.setAclPrincipals(confirmed.aclPrincipals());
        row.setDataLabel("CONFIDENTIAL");
        versions.insert(row);

        versions.supersedeOthers(instanceKey, version);

        // ont_value_index 重建（topics 从对应 def 的路径目录桥接）
        rebuildValueIndex(instanceKey, version, model, defKey);

        // outbox：tioka.ontology.instance_confirmed.v1（契约 docs/contracts/events/…）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceKey);
        out.put("versionId", String.valueOf(version));
        out.put("contentHash", confirmed.contentHash());
        out.put("modelKey", defKey);
        publisher.publish(new DomainEvent(
                OntologyMqTopics.INSTANCE_CONFIRMED,
                OntologyMqTopics.TOPIC,
                "instance:" + instanceKey,
                out,
                0,
                null,
                "instance:" + instanceKey));
    }

    private void validateAgainstSchemas(String defKey, int defVersion, JsonNode model) {
        SchemaRegistry registry = schemaRegistry.getIfAvailable();
        if (registry == null) {
            log.debug("SchemaRegistry 未装配，跳过确认前 Schema 校验");
            return;
        }
        List<String> errors = new ArrayList<>();
        for (var it = model.fields(); it.hasNext(); ) {
            var field = it.next();
            JsonNode value = field.getValue();
            if (!value.isArray()) {
                continue;
            }
            String schemaRef = defKey + "." + field.getKey() + "@" + defVersion;
            if (registry.get(schemaRef).isEmpty()) {
                continue;
            }
            int i = 0;
            for (JsonNode element : value) {
                List<com.tioka.ontology.api.schema.SchemaError> errs = registry.validate(schemaRef, element);
                for (com.tioka.ontology.api.schema.SchemaError e : errs) {
                    errors.add(schemaRef + "[" + i + "]" + e.pointer() + " " + e.code() + " " + e.message());
                }
                i++;
            }
        }
        if (!errors.isEmpty()) {
            log.warn("{}: {}", ERROR_SCHEMA_INVALID, errors);
            throw new IllegalStateException(ERROR_SCHEMA_INVALID + ": " + String.join("; ", errors));
        }
    }

    private void rebuildValueIndex(String instanceKey, int version, JsonNode model, String defKey) {
        Map<String, List<String>> topicsByPath = topicsFromCatalog(defKey);
        List<OntValueIndexDO> rows = new ArrayList<>();
        for (ValueIndexWalker.IndexPath entry : ValueIndexWalker.walk(model)) {
            OntValueIndexDO row = new OntValueIndexDO();
            row.setInstanceKey(instanceKey);
            row.setVersion(version);
            row.setPath(entry.path());
            row.setStatus(entry.status().stored());
            row.setValueText(entry.valueText());
            row.setTopics(topicsByPath.getOrDefault(structuralKeyOf(entry.path()), List.of()));
            rows.add(row);
        }
        valueIndex.rebuild(instanceKey, version, rows);
    }

    /** def 路径目录 → 结构键 topics 映射（DefinitionService 未装配时为空）。 */
    private Map<String, List<String>> topicsFromCatalog(String defKey) {
        DefinitionService service = definitions.getIfAvailable();
        if (service == null) {
            return Map.of();
        }
        Optional<PublishedDefinition> draft = service.getDraft(defKey);
        if (draft.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (PathCatalogEntry entry : draft.orElseThrow().pathCatalog()) {
            out.computeIfAbsent(structuralKeyOf(entry.path()), k -> new ArrayList<>())
                    .addAll(entry.topics());
        }
        return out;
    }

    /** 实例路径 → 结构键（去选择器）：m.feeItems[RENT@S1].sched.segments[0].amount → m.feeItems.sched.segments.amount */
    static String structuralKeyOf(String instancePath) {
        StringBuilder sb = new StringBuilder();
        boolean inSelector = false;
        for (int i = 0; i < instancePath.length(); i++) {
            char c = instancePath.charAt(i);
            if (c == '[') {
                inSelector = true;
                continue;
            }
            if (c == ']') {
                inSelector = false;
                continue;
            }
            if (!inSelector) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String writeDiff(List<ModelPathDiff.Entry> diff) {
        try {
            return mapper.writeValueAsString(mapper.valueToTree(diff));
        } catch (Exception e) {
            throw new IllegalStateException("diff 序列化失败", e);
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("model_json 解析失败", e);
        }
    }

    private ContractModelConfirmed parse(Map<String, Object> payload) {
        String contractKey = str(payload.get("contractKey"));
        Object version = payload.get("version");
        String contentHash = str(payload.get("contentHash"));
        String confirmedBy = str(payload.get("confirmedBy"));
        if (contractKey == null || version == null || contentHash == null || confirmedBy == null) {
            throw new IllegalArgumentException(
                    "contract.model.confirmed 缺必填字段（contractKey/version/contentHash/confirmedBy）");
        }
        if (!(version instanceof Number n) || n.intValue() < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
        List<String> aclPrincipals = new ArrayList<>();
        if (payload.get("aclPrincipals") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    aclPrincipals.add(o.toString());
                }
            }
        }
        return new ContractModelConfirmed(contractKey, n.intValue(), contentHash, confirmedBy, aclPrincipals);
    }

    private static String refsOf(JsonNode model) {
        JsonNode refs = model.get("refs");
        return refs == null ? null : refs.toString();
    }

    private static String storeKeyOf(String instanceKey) {
        // {domain}:{门店编码}:{合同号} → {domain}:{门店编码}
        int first = instanceKey.indexOf(':');
        int second = first < 0 ? -1 : instanceKey.indexOf(':', first + 1);
        return second > 0 ? instanceKey.substring(0, second) : instanceKey;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}

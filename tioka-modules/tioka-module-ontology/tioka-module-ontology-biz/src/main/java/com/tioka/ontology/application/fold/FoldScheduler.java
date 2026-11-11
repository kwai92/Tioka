package com.tioka.ontology.application.fold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.api.mq.OntologyMqTopics;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.pack.DomainPackLoader;
import com.tioka.ontology.application.pack.FoldingEngine;
import com.tioka.ontology.application.pack.ProjectionHook;
import com.tioka.ontology.application.pack.PureFunctionExecutor;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntStateRepository;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntStateDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 折叠调度（ONT-07，M3-03 §12）：消费 {@code tioka.ontology.instance_confirmed.v1}，按 store_key 聚合：
 *
 * <pre>
 * 1. payload 不含 storeKey → 用 instanceKey 回查（find / findLatestConfirmed）取 store_key；
 *    查不到 → 记日志正常 ACK（幂等吸收，不进死信）。
 * 2. listConfirmedByStore(storeKey) 取全量确认版本；算 input_hash
 *    （{instanceKey:version} 键值对按 key 排序拼接 SHA-256 hex，乱序同哈希）。
 * 3. 与 ont_state.input_hash 相同 → 跳过（ACK）。
 * 4. Redis 锁 ont:fold:{storeKey}（60s 租约，不续约）；拿不到 → 抛错让消息重试
 *    （不同门店并行，同门店串行）。
 * 5. 领域包 FoldingEngine 缺失 → 记日志跳过折叠、不写 ont_state、正常 ACK（降级口径已冻结）。
 *    存在 → PureFunctionExecutor.run 包裹执行 fold（30s 超时）得 stateMap。
 * 6. upsert ont_state（input_hash / state_json / review_items / folded_at / written_back=false）。
 * 7. 依次调 DomainPack.projections() 各 ProjectionHook.project（全部成功 → written_back=true）。
 * 8. outbox 发 tioka.ontology.state_folded.v1（instanceId=触发实例键；stateVersion=inputHash；
 *    补 storeKey / inputHash / writtenBack——粒度偏差已在台账登记）。
 * </pre>
 *
 * <p>幂等性：input_hash 去重使折叠结果与确认事件到达顺序无关（{@code orderIndependent}）。
 */
public class FoldScheduler extends IdempotentConsumer<Map<String, Object>> {

    static final String ERROR_LOCK_BUSY = "ONT_FOLD_LOCK_BUSY";

    private static final Logger log = LoggerFactory.getLogger(FoldScheduler.class);

    private final ObjectMapper mapper;
    private final OntInstanceVersionRepository versions;
    private final OntStateRepository states;
    private final FoldLock lock;
    private final ObjectProvider<DomainPackLoader> packLoader;
    private final ObjectProvider<DefinitionService> definitions;
    private final PureFunctionExecutor executor;
    private final EventPublisher publisher;

    public FoldScheduler(ObjectMapper mapper,
                         OntInstanceVersionRepository versions,
                         OntStateRepository states,
                         FoldLock lock,
                         ObjectProvider<DomainPackLoader> packLoader,
                         ObjectProvider<DefinitionService> definitions,
                         PureFunctionExecutor executor,
                         EventPublisher publisher) {
        this.mapper = mapper;
        this.versions = versions;
        this.states = states;
        this.lock = lock;
        this.packLoader = packLoader;
        this.definitions = definitions;
        this.executor = executor;
        this.publisher = publisher;
    }

    @Override
    protected String consumerName() {
        return "ontology-fold";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return OntologyMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return OntologyMqTopics.INSTANCE_CONFIRMED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String instanceKey = str(payload.get("instanceId"));
        if (instanceKey == null) {
            log.warn("instance_confirmed 缺 instanceId，跳过（ACK）");
            return;
        }
        // 1. 回查 store_key（find 按 versionId，缺省 findLatestConfirmed）；查不到 → 幂等吸收 ACK
        OntInstanceVersionDO trigger = resolveTrigger(instanceKey, payload).orElse(null);
        if (trigger == null) {
            log.info("instance_confirmed 无对应确认版本，幂等吸收 instanceKey={}", instanceKey);
            return;
        }
        String storeKey = trigger.getStoreKey();

        // 2. 全量确认版本 + input_hash
        List<OntInstanceVersionDO> confirmed = versions.listConfirmedByStore(storeKey);
        if (confirmed.isEmpty()) {
            log.info("storeKey={} 无 CONFIRMED 版本，跳过折叠（ACK）", storeKey);
            return;
        }
        String inputHash = inputHash(confirmed);

        // 3. input_hash 去重
        Optional<OntStateDO> existing = states.find(storeKey);
        if (existing.isPresent() && inputHash.equals(existing.orElseThrow().getInputHash())) {
            log.info("input_hash 未变化，跳过折叠 storeKey={} hash={}", storeKey, inputHash);
            return;
        }

        // 4. 领域包折叠引擎（缺失 → 降级 ACK，不写 ont_state）
        Optional<FoldingEngine> engine = foldingEngineOf(trigger);
        if (engine.isEmpty()) {
            log.info("领域包折叠引擎未装配，跳过折叠 storeKey={}（降级 ACK，不写 ont_state）", storeKey);
            return;
        }

        // 5. 同门店串行锁；拿不到 → 抛错重试
        Optional<FoldLock.Handle> handle = lock.tryAcquire(storeKey);
        if (handle.isEmpty()) {
            throw new IllegalStateException(ERROR_LOCK_BUSY + ": ont:fold:" + storeKey);
        }
        try (FoldLock.Handle ignored = handle.orElseThrow()) {
            // 锁内二次去重（并发确认窗口内可能已被上次折叠覆盖）
            if (states.find(storeKey).map(s -> inputHash.equals(s.getInputHash())).orElse(false)) {
                log.info("锁内二次去重 storeKey={} hash={}", storeKey, inputHash);
                return;
            }
            fold(storeKey, instanceKey, confirmed, inputHash, engine.orElseThrow());
        }
    }

    /** 步骤 5〜8：折叠 → upsert → 投影写回 → state_folded（由投递适配器事务包裹 outbox）。 */
    void fold(String storeKey, String triggerInstanceKey, List<OntInstanceVersionDO> confirmed,
              String inputHash, FoldingEngine engine) {
        List<Map<String, Object>> chain = confirmed.stream().map(this::modelOf).toList();
        Map<String, Object> stateMap = executor.run(() -> engine.fold(storeKey, chain));

        Object review = stateMap.get("reviewItems");
        String reviewItems = review instanceof List<?> ? writeValue(review) : "[]";

        OntStateDO row = new OntStateDO();
        row.setStoreKey(storeKey);
        row.setPackKey(packKeyOf(triggerInstanceKey, confirmed));
        row.setInputHash(inputHash);
        row.setStateJson(writeValue(stateMap));
        row.setReviewItems(reviewItems);
        row.setFoldedAt(Instant.now());
        row.setWrittenBack(false);
        states.upsert(row);

        // 投影写回：全部成功 → written_back = true
        boolean writtenBack = projectAll(triggerInstanceKey, stateMap);
        if (writtenBack) {
            states.markWrittenBack(storeKey);
        }

        // outbox：tioka.ontology.state_folded.v1（MANDATORY 事务由投递适配器提供）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", triggerInstanceKey);
        out.put("stateVersion", inputHash);
        out.put("storeKey", storeKey);
        out.put("inputHash", inputHash);
        out.put("writtenBack", writtenBack);
        publisher.publish(new DomainEvent(
                OntologyMqTopics.STATE_FOLDED,
                OntologyMqTopics.TOPIC,
                "store:" + storeKey,
                out,
                0,
                null,
                "store:" + storeKey));
    }

    private boolean projectAll(String triggerInstanceKey, Map<String, Object> stateMap) {
        DomainPackLoader loader = packLoader.getIfAvailable();
        if (loader == null) {
            return false;
        }
        boolean allOk = true;
        for (ProjectionHook hook : packHooks(loader, triggerInstanceKey)) {
            try {
                executor.run(() -> hook.project(stateMap));
            } catch (RuntimeException e) {
                log.warn("投影钩子 {} 失败：{}", hook.hookId(), e.toString());
                allOk = false;
            }
        }
        return allOk;
    }

    private Optional<OntInstanceVersionDO> resolveTrigger(String instanceKey, Map<String, Object> payload) {
        Object versionId = payload.get("versionId");
        if (versionId != null) {
            try {
                Optional<OntInstanceVersionDO> row = versions.find(instanceKey, Integer.parseInt(versionId.toString()));
                if (row.isPresent()) {
                    return row;
                }
            } catch (NumberFormatException ignored) {
                // versionId 非数字 → 回退 findLatestConfirmed
            }
        }
        return versions.findLatestConfirmed(instanceKey);
    }

    /** 触发实例 defKey/defVersion → PublishedDefinition → packKey；再取该包的折叠引擎。 */
    private Optional<FoldingEngine> foldingEngineOf(OntInstanceVersionDO trigger) {
        DomainPackLoader loader = packLoader.getIfAvailable();
        if (loader == null) {
            return Optional.empty();
        }
        PublishedDefinition def = definitions.getIfAvailable() == null ? null
                : definitions.getObject().getVersion(trigger.getDefKey(), trigger.getDefVersion())
                        .or(() -> definitions.getObject().currentPublishedVersion(trigger.getDefKey()))
                        .orElseGet(() -> definitions.getObject().getDraft(trigger.getDefKey()).orElse(null));
        if (def == null) {
            return Optional.empty();
        }
        return loader.folding(def.packKey(), def.packVersion());
    }

    private List<ProjectionHook> packHooks(DomainPackLoader loader, String triggerInstanceKey) {
        // 钩子与折叠同包：按触发实例 def 的 pack 解析（fold 时一并传包键更简洁，保持 SPI 不动）
        Optional<OntInstanceVersionDO> trigger = versions.findLatestConfirmed(triggerInstanceKey);
        if (trigger.isEmpty()) {
            return List.of();
        }
        PublishedDefinition def = definitions.getIfAvailable() == null ? null
                : definitions.getObject().getVersion(trigger.orElseThrow().getDefKey(), trigger.orElseThrow().getDefVersion())
                        .or(() -> definitions.getObject().currentPublishedVersion(trigger.orElseThrow().getDefKey()))
                        .orElseGet(() -> definitions.getObject().getDraft(trigger.orElseThrow().getDefKey()).orElse(null));
        if (def == null) {
            return List.of();
        }
        return loader.projections(def.packKey(), def.packVersion());
    }

    private String packKeyOf(String triggerInstanceKey, List<OntInstanceVersionDO> confirmed) {
        DomainPackLoader loader = packLoader.getIfAvailable();
        DefinitionService defs = definitions.getIfAvailable();
        if (loader != null && defs != null) {
            Optional<OntInstanceVersionDO> trigger = confirmed.stream()
                    .filter(r -> r.getInstanceKey().equals(triggerInstanceKey))
                    .findFirst();
            String defKey = trigger.map(OntInstanceVersionDO::getDefKey).orElse(confirmed.get(0).getDefKey());
            int defVersion = trigger.map(OntInstanceVersionDO::getDefVersion).orElse(confirmed.get(0).getDefVersion());
            PublishedDefinition def = defs.getVersion(defKey, defVersion)
                    .or(() -> defs.currentPublishedVersion(defKey))
                    .orElseGet(() -> defs.getDraft(defKey).orElse(null));
            if (def != null) {
                return def.packKey();
            }
        }
        return "unknown";
    }

    /**
     * input_hash：{instanceKey:version} 键值对按 key 排序后拼接（\n 分隔）SHA-256 hex。
     * 排序保证乱序同哈希（{@code orderIndependent}）。
     */
    static String inputHash(List<OntInstanceVersionDO> confirmed) {
        Map<String, String> entries = new TreeMap<>();
        for (OntInstanceVersionDO row : confirmed) {
            entries.put(row.getInstanceKey() + ":" + row.getVersion(), "");
        }
        return sha256Hex(String.join("\n", entries.keySet()));
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Map<String, Object> modelOf(OntInstanceVersionDO row) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = mapper.readValue(row.getModelJson(), Map.class);
            return model;
        } catch (Exception e) {
            throw new IllegalStateException("model_json 解析失败: " + row.getInstanceKey(), e);
        }
    }

    private String writeValue(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("state 序列化失败", e);
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}

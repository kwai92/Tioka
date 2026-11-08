package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.infrastructure.config.CfgStore;
import com.tioka.governance.infrastructure.persistence.CfgVersionDO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Redis pub/sub 广播配置发布/回滚（ADR-041 §2；不经 outbox）。
 * 频道：{@code cfg:version}；载荷对齐 {@link GovernanceMqTopics#TAG_VERSION_PUBLISHED}。
 */
public class ConfigVersionRefreshBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionRefreshBus.class);

    public static final String CHANNEL = "cfg:version";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisMessageListenerContainer container;
    private final ConfigProjectionCache cache;
    private final ConfigCurrentPointerStore pointers;
    private final CfgStore store;
    private final ConfigSpecNormalizer normalizer;

    public ConfigVersionRefreshBus(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            RedisMessageListenerContainer container,
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            CfgStore store,
            ConfigSpecNormalizer normalizer) {
        this.redis = redis;
        this.mapper = mapper;
        this.container = container;
        this.cache = cache;
        this.pointers = pointers;
        this.store = store;
        this.normalizer = normalizer;
    }

    @PostConstruct
    public void subscribe() {
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        if (!container.isRunning()) {
            container.start();
        }
    }

    @PreDestroy
    public void unsubscribe() {
        container.removeMessageListener(this);
    }

    public void publishEvent(ObjectRef ref, int newVersionNo, Integer oldVersionNo, String action, String versionId) {
        try {
            var node = mapper.createObjectNode();
            node.put("objectType", ref.objectType());
            node.put("objectId", ref.objectId());
            node.put("newVersionNo", newVersionNo);
            if (oldVersionNo == null) {
                node.putNull("oldVersionNo");
            } else {
                node.put("oldVersionNo", oldVersionNo);
            }
            node.put("action", action);
            node.put("versionId", versionId);
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("config version broadcast failed: {}", e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(payload);
            ObjectRef ref = new ObjectRef(node.path("objectType").asText(), node.path("objectId").asText());
            String versionId = node.path("versionId").asText(null);
            int versionNo = node.path("newVersionNo").asInt();
            applyLocal(ref, versionNo, versionId);
        } catch (Exception e) {
            log.warn("config version refresh handle failed: {}", e.toString());
        }
    }

    /** 拉取版本写入本地缓存（pub/sub 与对账共用）。 */
    public void applyLocal(ObjectRef ref, int versionNo, String versionId) {
        Optional<CfgVersionDO> row = versionId != null && !versionId.isBlank()
                ? store.findVersionById(versionId)
                : store.findVersion(ref.objectType(), ref.objectId(), versionNo);
        if (row.isEmpty()) {
            cache.invalidate(ref);
            return;
        }
        CfgVersionDO v = row.get();
        JsonNode spec = normalizer.readTree(v.getSpec());
        cache.put(ref, new ConfigProjectionCache.Entry(
                v.getVersionId(), v.getVersionNo(), spec, v.getContentHash(), v.getStatus()));
        pointers.set(ref, v.getVersionNo(), v.getVersionId());
    }
}

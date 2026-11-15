package com.tioka.model.application.health;

import com.tioka.framework.id.Ids;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MH-10（ADR-041 第 5.1 节）：供应商主动探测。
 *
 * <p>默认每 60 秒（{@code tioka.model.probe.interval-ms}）由一个实例执行（Redis SETNX 锁，多实例互斥）：
 * 对每个供应商调用其免费模型列表接口（{@code GET {baseUrl}{path}}，默认 {@code /models}），
 * 经出网守卫 {@link EgressClient} 发送；可选开启「1 token 补全」探测（{@code tioka.model.probe.completion-probe}，默认关闭）。
 * 结论写 {@link ModelProbeStore}（连续失败计数），随后触发 {@link ModelHealthService} 重算并回写健康列。
 *
 * <p>mock 模式（{@code tioka.model.mock=true}）下不发真实出网请求，直接跳过探测。
 * 任何异常只记日志，不影响调度；Redis 缺席时退化为单实例执行。
 */
@Component
public class ModelHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthProbe.class);
    private static final String LOCK_KEY = "mh:probe:lock";

    /** 本实例的锁持有者标识（多实例互斥）。 */
    private final String lockHolder = Ids.newId("probeblock");

    private final ModelEntitiesRepository entities;
    private final ModelHealthService healthService;
    private final ModelProbeStore probeStore;
    private final SecretStore secretStore;
    private final EgressClient egress;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<ProviderClient> providerClient;
    private final boolean enabled;
    private final boolean mock;
    private final boolean completionProbe;
    private final String modelListPath;
    private final long intervalMs;

    public ModelHealthProbe(ModelEntitiesRepository entities,
                            ModelHealthService healthService,
                            ModelProbeStore probeStore,
                            SecretStore secretStore,
                            EgressClient egress,
                            ObjectProvider<StringRedisTemplate> redis,
                            ObjectProvider<ProviderClient> providerClient,
                            @Value("${tioka.model.probe.enabled:true}") boolean enabled,
                            @Value("${tioka.model.mock:true}") boolean mock,
                            @Value("${tioka.model.probe.completion-probe:false}") boolean completionProbe,
                            @Value("${tioka.model.probe.model-list-path:/models}") String modelListPath,
                            @Value("${tioka.model.probe.interval-ms:60000}") long intervalMs) {
        this.entities = entities;
        this.healthService = healthService;
        this.probeStore = probeStore;
        this.secretStore = secretStore;
        this.egress = egress;
        this.redis = redis;
        this.providerClient = providerClient;
        this.enabled = enabled;
        this.mock = mock;
        this.completionProbe = completionProbe;
        this.modelListPath = modelListPath == null || modelListPath.isBlank() ? "/models" : modelListPath;
        this.intervalMs = Math.max(5_000L, intervalMs);
    }

    /** 定时探测入口。 */
    @Scheduled(fixedDelayString = "${tioka.model.probe.interval-ms:60000}")
    public void scheduledProbe() {
        probe();
    }

    /** 单次探测（可测试直接调用）。 */
    public void probe() {
        if (!enabled) {
            return;
        }
        StringRedisTemplate t = redis == null ? null : redis.getIfAvailable();
        if (t != null && !tryLock(t)) {
            return;
        }
        try {
            if (mock) {
                log.debug("model probe skipped: mock mode");
                return;
            }
            for (ModelProviderDO p : entities.providers()) {
                probeOne(p);
            }
            healthService.refreshAllProviders();
        } catch (RuntimeException e) {
            log.warn("model probe run failed: {}", e.toString());
        }
    }

    private void probeOne(ModelProviderDO p) {
        if (p == null || blank(p.getBaseUrl())) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(p.getBaseUrl()) + modelListPath))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + resolveApiKey(p.getApiKeyRef()))
                    .GET()
                    .build();
            HttpResponse<String> resp = egress.send(req);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                recordResult(p, true, null, now);
                return;
            }
            recordResult(p, false, "model-list " + resp.statusCode(), now);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            recordResult(p, false, "model-list " + e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }
    }

    /** 结论落库（连续失败计数）并触发可选补全探测。 */
    private void recordResult(ModelProviderDO p, boolean ok, String message, long now) {
        if (ok) {
            if (completionProbe) {
                completionProbe(p, now);
                return;
            }
            probeStore.recordSuccess(p.getId(), now);
            return;
        }
        probeStore.recordFailure(p.getId(), message, now);
    }

    /** 可选「1 token 补全」探测：用该供应商第一个绑定的模型名做最小补全调用（默认关闭）。 */
    private void completionProbe(ModelProviderDO p, long now) {
        ProviderClient client = providerClient == null ? null : providerClient.getIfAvailable();
        if (client == null) {
            probeStore.recordSuccess(p.getId(), now);
            return;
        }
        String model = firstModelOf(p.getId());
        if (model == null) {
            probeStore.recordSuccess(p.getId(), now);
            return;
        }
        try {
            List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "ping"));
            client.complete(p.getBaseUrl(), p.getApiKeyRef(), model, messages, List.of());
            probeStore.recordSuccess(p.getId(), now);
        } catch (Exception e) {
            probeStore.recordFailure(p.getId(),
                    "completion " + e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }
    }

    private String firstModelOf(String providerId) {
        for (ModelBindingDO b : entities.bindings()) {
            if (providerId.equals(b.getProviderId())) {
                return b.getModelName();
            }
        }
        return null;
    }

    private String resolveApiKey(String apiKeyRef) {
        String ns = "model-provider";
        String key = apiKeyRef;
        if (apiKeyRef != null && apiKeyRef.contains("::")) {
            String[] parts = apiKeyRef.split("::", 2);
            ns = parts[0];
            key = parts[1];
        }
        String apiKey = secretStore.get(ns, key);
        return apiKey == null ? "" : apiKey;
    }

    private boolean tryLock(StringRedisTemplate t) {
        try {
            Boolean ok = t.opsForValue().setIfAbsent(LOCK_KEY, lockHolder,
                    Duration.ofMillis(intervalMs));
            return Boolean.TRUE.equals(ok) || lockHolder.equals(t.opsForValue().get(LOCK_KEY));
        } catch (RuntimeException e) {
            log.warn("probe lock failed, continue single-instance: {}", e.toString());
            return true;
        }
    }

    private static String trimSlash(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

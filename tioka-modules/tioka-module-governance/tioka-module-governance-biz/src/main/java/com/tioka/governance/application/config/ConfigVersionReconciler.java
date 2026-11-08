package com.tioka.governance.application.config;

import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每 30s 比对 Redis 当前指针与本地缓存，防 pub/sub 丢消息（ADR-041 §2）。
 */
public class ConfigVersionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionReconciler.class);

    private final ConfigProjectionCache cache;
    private final ConfigCurrentPointerStore pointers;
    private final ConfigVersionRefreshBus bus;

    public ConfigVersionReconciler(
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            ConfigVersionRefreshBus bus) {
        this.cache = cache;
        this.pointers = pointers;
        this.bus = bus;
    }

    @Scheduled(fixedDelayString = "${tioka.config.version.reconcile-interval-ms:30000}")
    public void scheduledReconcile() {
        reconcile();
    }

    /** 对账一次；测试可直接调用。 */
    public void reconcile() {
        for (var entry : cache.snapshot().entrySet()) {
            String composite = entry.getKey();
            int sep = composite.indexOf('\0');
            if (sep <= 0) {
                continue;
            }
            ObjectRef ref = new ObjectRef(composite.substring(0, sep), composite.substring(sep + 1));
            var remote = pointers.get(ref);
            if (remote.isEmpty()) {
                continue;
            }
            ConfigProjectionCache.Entry local = entry.getValue();
            if (local.versionNo() != remote.get().versionNo()
                    || !local.versionId().equals(remote.get().versionId())) {
                log.debug("config projection drift {}/{} local={} remote={}",
                        ref.objectType(), ref.objectId(), local.versionNo(), remote.get().versionNo());
                bus.applyLocal(ref, remote.get().versionNo(), remote.get().versionId());
            }
        }
        // 本地没有、但 Redis 有指针的对象：由调用方首次 getCurrent 时懒加载；对账只修已知键
    }

    /** 按 Redis 指针强制刷新指定对象（测试与懒加载）。 */
    public void refreshFromPointer(ObjectRef ref) {
        pointers.get(ref).ifPresent(p -> bus.applyLocal(ref, p.versionNo(), p.versionId()));
    }
}
